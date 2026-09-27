#!/usr/bin/env python3
"""Build a diskless console test initramfs from a supplied static AArch64 BusyBox.
Usage: python3 tools/build-console-initramfs.py BUSYBOX OUTPUT
No Debian root disk, ttyd, network or guest agent is needed.
"""
import gzip
import pathlib
import sys

archive = bytearray()
def entry(name, mode, data=b'', major=0, minor=0):
    name = name.encode() + b'\0'
    fields = [1, mode, 0, 0, 1, 0, len(data), 0, 0, major, minor, len(name), 0]
    archive.extend(b'070701' + ''.join(f'{n:08x}' for n in fields).encode() + name)
    archive.extend(b'\0' * (-len(archive) % 4))
    archive.extend(data)
    archive.extend(b'\0' * (-len(archive) % 4))
for directory in ('bin', 'dev', 'proc', 'sys'):
    entry(directory, 0o40755)
entry('bin/busybox', 0o100755, pathlib.Path(sys.argv[1]).read_bytes())
entry('bin/sh', 0o120777, b'busybox')
entry('dev/console', 0o20600, major=5, minor=1)
entry('init', 0o100755, b'''#!/bin/sh
/bin/busybox mount -t devtmpfs devtmpfs /dev
/bin/busybox mount -t proc proc /proc
/bin/busybox mount -t sysfs sysfs /sys
/bin/busybox --install -s /bin
export PATH=/bin TERM=vt100
exec /bin/busybox setsid /bin/busybox sh -c 'exec sh </dev/hvc0 >/dev/hvc0 2>&1'
''')
entry('TRAILER!!!', 0)
pathlib.Path(sys.argv[2]).write_bytes(gzip.compress(bytes(archive), mtime=0))
