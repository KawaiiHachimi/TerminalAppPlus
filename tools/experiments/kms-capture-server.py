#!/usr/bin/env python3
"""Experimental primary-plane capture on the tested virtio-gpu guest.
Run with guest sudo, not Android root. No TCP listener, configuration changes or input injection.
Only host CID 2 may request frames. Linear XR24 only; not a universal screen recorder.
"""
import os,fcntl,struct,mmap,re,zlib,binascii,socket,time
from pathlib import Path
from contextlib import ExitStack

def capture():
    with ExitStack() as stack:
        state=Path('/sys/kernel/debug/dri/0/state').read_text()
        fb=int(re.search(r'\n\s*fb=(\d+)',state).group(1))
        fd=os.open('/dev/dri/card0',os.O_RDWR|os.O_CLOEXEC)
        stack.callback(os.close, fd)
        cmd=bytearray(104);struct.pack_into('I',cmd,0,fb)
        fcntl.ioctl(fd,0xc06864ce,cmd,True)
        _,w,h,fmt,flags=struct.unpack_from('5I',cmd)
        handle=struct.unpack_from('I',cmd,20)[0];pitch=struct.unpack_from('I',cmd,36)[0];offset=struct.unpack_from('I',cmd,52)[0]
        modifier=struct.unpack_from('Q',cmd,72)[0]
        assert 0 < w <= 4096 and 0 < h <= 4096 and pitch <= 65536
        assert fmt==0x34325258 and modifier==0 and handle
        prime=bytearray(struct.pack('IIi',handle,os.O_CLOEXEC | os.O_RDWR,-1));fcntl.ioctl(fd,0xc00c642d,prime,True)
        dmafd=struct.unpack('IIi',prime)[2]
        stack.callback(os.close, dmafd)
        try:
         pixels=mmap.mmap(dmafd,offset+pitch*h,flags=mmap.MAP_SHARED,prot=mmap.PROT_READ)
        except OSError:
         dumb=bytearray(struct.pack('IIQ',handle,0,0));fcntl.ioctl(fd,0xc01064b3,dumb,True)
         pixels=mmap.mmap(fd,offset+pitch*h,flags=mmap.MAP_SHARED,prot=mmap.PROT_READ,offset=struct.unpack('IIQ',dumb)[2])
        stack.callback(pixels.close)
        raw=bytearray()
        for y in range(h):
         row=pixels[offset+y*pitch:offset+y*pitch+w*4]
         rgb=bytearray(w*3);rgb[0::3]=row[2::4];rgb[1::3]=row[1::4];rgb[2::3]=row[0::4]
         raw.append(0);raw.extend(rgb)
        def chunk(kind,data):return struct.pack('!I',len(data))+kind+data+struct.pack('!I',binascii.crc32(kind+data)&0xffffffff)
        png = (b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('!2I5B',w,h,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(raw))+chunk(b'IEND',b''))
        return png

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
                    while connection.recv(1) == b'F':
                        frame = capture()
                        connection.sendall(struct.pack('!I', len(frame)) + frame)
                        time.sleep(0.25)
                except (ConnectionError, TimeoutError, OSError, AssertionError):
                    pass

if __name__ == '__main__':
    main()
