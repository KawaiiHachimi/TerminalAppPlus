#!/usr/bin/env python3
"""Capture active virtio-gpu scanout and its linear ARGB cursor plane.
Guest sudo is required; Android root is not. Only host CID 2 may request frames.
No desktop/session configuration or input injection is performed by this server.
"""
import binascii
from contextlib import ExitStack
import fcntl
import mmap
import os
from pathlib import Path
import re
import socket
import struct
import time
import zlib

XR24 = 0x34325258
AR24 = 0x34325241


def active_planes(state):
    blocks = re.split(r'(?m)^(?=(?:plane|crtc|connector)\[)', state)
    active = set()
    for block in blocks:
        name = re.match(r'crtc\[\d+\]: (\S+)', block)
        if name and re.search(r'^\s*enable=1$', block, re.M) and re.search(r'^\s*active=1$', block, re.M):
            active.add(name[1])
    planes = []
    for block in blocks:
        if not block.startswith('plane['):
            continue
        crtc = re.search(r'^\s*crtc=(\S+)', block, re.M)
        fb = re.search(r'^\s*fb=(\d+)', block, re.M)
        if not (crtc and fb and crtc[1] in active and int(fb[1]) > 0):
            continue
        z = re.search(r'^\s*normalized-zpos=(\d+)', block, re.M)
        rect = re.search(r'^\s*crtc-pos=(\d+)x(\d+)([+-]\d+)([+-]\d+)', block, re.M)
        planes.append({'fb': int(fb[1]), 'crtc': crtc[1], 'z': int(z[1]) if z else 0,
                       'rect': tuple(map(int, rect.groups())) if rect else None})
    if not planes:
        raise OSError('No active DRM scanout')
    return sorted(planes, key=lambda plane: plane['z'])


def active_framebuffer(state):
    return active_planes(state)[0]['fb']


def read_framebuffer(fd, fb, stack):
    cmd = bytearray(104)
    struct.pack_into('I', cmd, 0, fb)
    fcntl.ioctl(fd, 0xc06864ce, cmd, True)  # DRM_IOCTL_MODE_GETFB2
    _, width, height, pixel_format, _ = struct.unpack_from('5I', cmd)
    handle = struct.unpack_from('I', cmd, 20)[0]
    pitch = struct.unpack_from('I', cmd, 36)[0]
    offset = struct.unpack_from('I', cmd, 52)[0]
    modifier = struct.unpack_from('Q', cmd, 72)[0]
    if not (0 < width <= 4096 and 0 < height <= 4096 and width*4 <= pitch <= 65536
            and pixel_format in (XR24, AR24) and modifier == 0 and handle):
        raise OSError('Unsupported scanout format or layout')
    prime = bytearray(struct.pack('IIi', handle, os.O_CLOEXEC | os.O_RDWR, -1))
    fcntl.ioctl(fd, 0xc00c642d, prime, True)
    dmafd = struct.unpack('IIi', prime)[2]
    stack.callback(os.close, dmafd)
    try:
        pixels = mmap.mmap(dmafd, offset+pitch*height, flags=mmap.MAP_SHARED, prot=mmap.PROT_READ)
    except OSError:
        dumb = bytearray(struct.pack('IIQ', handle, 0, 0))
        fcntl.ioctl(fd, 0xc01064b3, dumb, True)
        pixels = mmap.mmap(fd, offset+pitch*height, flags=mmap.MAP_SHARED,
                          prot=mmap.PROT_READ, offset=struct.unpack('IIQ', dumb)[2])
    stack.callback(pixels.close)
    fcntl.ioctl(dmafd, 0x40086200, struct.pack('Q', 1))  # CPU READ START
    stack.callback(fcntl.ioctl, dmafd, 0x40086200, struct.pack('Q', 5))  # CPU READ END
    rgba = bytearray(width*height*4)
    for y in range(height):
        row = pixels[offset+y*pitch:offset+y*pitch+width*4]
        start = y*width*4
        rgba[start:start+width*4:4] = row[2::4]
        rgba[start+1:start+width*4:4] = row[1::4]
        rgba[start+2:start+width*4:4] = row[0::4]
        rgba[start+3:start+width*4:4] = row[3::4] if pixel_format == AR24 else b'\xff'*width
    return width, height, pixel_format, rgba


def compose_cursor(frame, width, height, cursor, cursor_width, cursor_height, x, y):
    """Blend a premultiplied ARGB cursor; clip negative/offscreen positions."""
    for cy in range(max(0, -y), min(cursor_height, height-y)):
        for cx in range(max(0, -x), min(cursor_width, width-x)):
            source = (cy*cursor_width+cx)*4
            alpha = cursor[source+3]
            if alpha == 0:
                continue
            target = ((y+cy)*width+x+cx)*4
            for channel in range(3):
                frame[target+channel] = min(255, cursor[source+channel] +
                    (frame[target+channel]*(255-alpha)+127)//255)


def capture(raw_output=False):
    with ExitStack() as stack:
        planes = active_planes(Path('/sys/kernel/debug/dri/0/state').read_text())
        primary = planes[0]
        fd = os.open('/dev/dri/card0', os.O_RDWR | os.O_CLOEXEC)
        stack.callback(os.close, fd)
        width, height, _, rgba = read_framebuffer(fd, primary['fb'], stack)
        for plane in planes[1:]:
            if plane['crtc'] != primary['crtc'] or not plane['rect']:
                continue
            pw, ph, x, y = plane['rect']
            if not (0 < pw <= 512 and 0 < ph <= 512):
                continue  # Cursor-sized planes only; general overlay composition is not implemented.
            try:
                cw, ch, fmt, cursor = read_framebuffer(fd, plane['fb'], stack)
                if fmt == AR24 and (cw, ch) == (pw, ph):
                    compose_cursor(rgba, width, height, cursor, cw, ch, x, y)
            except OSError:
                pass  # Cursor may disappear/change FB while a frame is being captured.
        if raw_output:
            return struct.pack('!III', width, height, len(rgba)) + rgba
        raw = bytearray()
        for y in range(height):
            row = rgba[y*width*4:(y+1)*width*4]
            rgb = bytearray(width*3)
            rgb[0::3], rgb[1::3], rgb[2::3] = row[0::4], row[1::4], row[2::4]
            raw.append(0)
            raw.extend(rgb)
        def chunk(kind, data):
            return struct.pack('!I', len(data))+kind+data+struct.pack('!I', binascii.crc32(kind+data) & 0xffffffff)
        return (b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR', struct.pack('!2I5B', width, height, 8, 2, 0, 0, 0))+
                chunk(b'IDAT', zlib.compress(raw))+chunk(b'IEND', b''))


def main():
    with socket.socket(socket.AF_VSOCK, socket.SOCK_STREAM) as server:
        server.bind((socket.VMADDR_CID_ANY, 7683))
        server.listen(1)
        while True:
            connection, address = server.accept()
            with connection:
                if address[0] != 2:
                    continue
                connection.settimeout(10)
                try:
                    while True:
                        request = connection.recv(1)
                        if request not in (b'F', b'R'):
                            break
                        frame = capture(request == b'R')
                        connection.sendall(frame if request == b'R' else struct.pack('!I', len(frame))+frame)
                        time.sleep(0.1 if request == b'R' else 0.25)
                except (ConnectionError, TimeoutError, OSError):
                    pass


if __name__ == '__main__':
    main()
