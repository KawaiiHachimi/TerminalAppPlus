#!/usr/bin/env python3
"""Capture active virtio-gpu scanout and its linear ARGB cursor plane.
Guest sudo is required; Android root is not. Only host CID 2 may request frames.
No desktop/session configuration or input injection is performed by this server.
"""
import argparse
import binascii
import ctypes
import ctypes.util
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


def reusable_buffer(buffers, key, length):
    value = buffers.get(key)
    if value is None or len(value) != length:
        value = buffers[key] = bytearray(length)
    return value


def frame_delay(deadline, now):
    return max(0.0, deadline - now)


def read_framebuffer(fd, fb, stack, buffers=None, role='primary', native=False):
    if buffers is None:
        buffers = {}
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
    length = width*height*4
    packed = reusable_buffer(buffers, role+'-packed', length)
    view = memoryview(pixels)
    try:
        if pitch == width*4:
            packed[:] = view[offset:offset+length]
        else:
            for y in range(height):
                packed[y*width*4:(y+1)*width*4] = view[offset+y*pitch:offset+y*pitch+width*4]
    finally:
        view.release()
    if native:
        return width, height, pixel_format, packed
    rgba = reusable_buffer(buffers, role+'-rgba', length)
    # Bulk channel conversion runs in C, rather than four Python slices per scanline.
    rgba[0::4], rgba[1::4], rgba[2::4] = packed[2::4], packed[1::4], packed[0::4]
    if pixel_format == AR24:
        rgba[3::4] = packed[3::4]
    elif buffers.get(role+'-alpha') != (width, height, pixel_format):
        rgba[3::4] = b'\xff'*(width*height)
    buffers[role+'-alpha'] = (width, height, pixel_format)
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


def capture(raw_output=False, buffers=None, split=False, native=False):
    if buffers is None:
        buffers = {}
    with ExitStack() as stack:
        planes = active_planes(Path('/sys/kernel/debug/dri/0/state').read_text())
        primary = planes[0]
        fd = os.open('/dev/dri/card0', os.O_RDWR | os.O_CLOEXEC)
        stack.callback(os.close, fd)
        width, height, _, rgba = read_framebuffer(fd, primary['fb'], stack, buffers, 'primary', native)
        for plane in planes[1:]:
            if plane['crtc'] != primary['crtc'] or not plane['rect']:
                continue
            pw, ph, x, y = plane['rect']
            if not (0 < pw <= 512 and 0 < ph <= 512):
                continue  # Cursor-sized planes only; general overlay composition is not implemented.
            try:
                cw, ch, fmt, cursor = read_framebuffer(fd, plane['fb'], stack, buffers, 'cursor', native)
                if fmt == AR24 and (cw, ch) == (pw, ph):
                    compose_cursor(rgba, width, height, cursor, cw, ch, x, y)
            except OSError:
                pass  # Cursor may disappear/change FB while a frame is being captured.
        if raw_output:
            header = struct.pack('!III', width, height, len(rgba))
            return (header, rgba) if split else header + rgba
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


def encode_frame(pixels):
    """Skip full compression for high-entropy images; preserve a lossless raw fallback."""
    view = memoryview(pixels)
    chunk_size = min(8192, len(view)//4)
    sample = b''.join(view[offset:offset+chunk_size] for offset in
                      (0, len(view)//4, len(view)//2, 3*len(view)//4))
    if not sample or len(zlib.compress(sample, 1)) >= len(sample)*0.8:
        return 0, view
    compressed = zlib.compress(view, 1)
    return (1, compressed) if len(compressed) < len(view) else (0, view)


class Lz4Encoder:
    """Use the guest's existing native LZ4 library when available; no pip dependency."""
    def __init__(self):
        self.destination = None
        try:
            self.library = ctypes.CDLL(ctypes.util.find_library('lz4') or 'liblz4.so.1')
            self.library.LZ4_compressBound.argtypes = [ctypes.c_int]
            self.library.LZ4_compressBound.restype = ctypes.c_int
            self.library.LZ4_compress_default.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int, ctypes.c_int]
            self.library.LZ4_compress_default.restype = ctypes.c_int
        except (OSError, AttributeError):
            self.library = None

    def encode(self, pixels):
        if self.library is None:
            return encode_frame(pixels)
        length = len(pixels)
        bound = self.library.LZ4_compressBound(length)
        if self.destination is None or len(self.destination) < bound:
            self.destination = ctypes.create_string_buffer(bound)
        source = (ctypes.c_char * length).from_buffer(pixels)
        size = self.library.LZ4_compress_default(source, self.destination, length, len(self.destination))
        if 0 < size < length:
            return 2, memoryview(self.destination).cast('B')[:size]
        return 0, memoryview(pixels)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fps', type=int, default=30, choices=range(1, 61), metavar='1..60')
    args = parser.parse_args()
    with socket.socket(socket.AF_VSOCK, socket.SOCK_STREAM) as server:
        server.bind((socket.VMADDR_CID_ANY, 7683))
        server.listen(1)
        while True:
            connection, address = server.accept()
            with connection:
                if address[0] != 2:
                    continue
                connection.settimeout(10)
                buffers = {}
                lz4 = Lz4Encoder()
                deadline = 0.0
                sample_start = time.monotonic()
                frames = 0
                capture_seconds = 0.0
                send_seconds = 0.0
                try:
                    while True:
                        request = connection.recv(1)
                        if request not in (b'F', b'R', b'Z', b'B', b'Q'):
                            break
                        time.sleep(frame_delay(deadline, time.monotonic()))
                        start = time.monotonic()
                        frame = capture(request in (b'R', b'Z', b'B', b'Q'), buffers=buffers, split=True, native=request in (b'B', b'Q'))
                        captured = time.monotonic()
                        if request in (b'Z', b'B', b'Q'):
                            header, pixels = frame
                            width, height, raw_length = struct.unpack('!III', header)
                            codec, payload = lz4.encode(pixels) if request == b'Q' else encode_frame(pixels)
                            connection.sendall(struct.pack('!IIIII', width, height, raw_length, len(payload), codec))
                            connection.sendall(payload)
                        elif request == b'R':
                            header, pixels = frame
                            connection.sendall(header)
                            connection.sendall(memoryview(pixels))
                        else:
                            connection.sendall(struct.pack('!I', len(frame)))
                            connection.sendall(frame)
                        end = time.monotonic()
                        # Budget capture/send time into the frame period; never add 100ms after it.
                        deadline = start + 1.0/(args.fps if request in (b'R', b'Z', b'B', b'Q') else min(4, args.fps))
                        frames += 1
                        capture_seconds += captured-start
                        send_seconds += end-captured
                        if end-sample_start >= 5:
                            print('capture: %.1f fps, %.1f ms capture, %.1f ms encode/send' %
                                  (frames/(end-sample_start), capture_seconds*1000/frames,
                                   send_seconds*1000/frames), flush=True)
                            sample_start=end; frames=0; capture_seconds=send_seconds=0.0
                except (ConnectionError, TimeoutError, OSError):
                    pass


if __name__ == '__main__':
    main()
