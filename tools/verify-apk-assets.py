#!/usr/bin/env python3
"""Check runtime asset names after AAPT packaging (not just source files)."""
import gzip
import io
from pathlib import Path
import sys
import tarfile
import zipfile
apk = Path(sys.argv[1] if len(sys.argv) > 1 else 'app/build/outputs/apk/debug/app-debug.apk')
with zipfile.ZipFile(apk) as archive:
    blob = archive.read('assets/guest-setup/guest-tools.bundle')
    with tarfile.open(fileobj=io.BytesIO(gzip.decompress(blob)), mode='r:') as guest:
        for name in ('install-guest-tools.sh', 'terminal-plus-guest.service', 'terminal-plus-guest.py', 'terminal-plus-capture.py', 'terminal-plus-proxy.py'):
            assert guest.extractfile(name).read() == Path('app/src/main/assets/guest-setup', name).read_bytes(), name
    assert archive.read('assets/bootloader/u-boot.bin') == Path('app/src/main/assets/bootloader/u-boot.bin').read_bytes()
print('APK runtime payload names, bytes and bootloader verified')
