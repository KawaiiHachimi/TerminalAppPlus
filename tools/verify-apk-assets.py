#!/usr/bin/env python3
"""Check runtime asset names after AAPT packaging (not just source files)."""
import gzip
import io
from pathlib import Path
import sys
import tarfile
import zipfile
import hashlib
import json
apk = Path(sys.argv[1] if len(sys.argv) > 1 else 'app/build/outputs/apk/debug/app-debug.apk')
with zipfile.ZipFile(apk) as archive:
    blob = archive.read('assets/guest-setup/guest-tools.bundle')
    with tarfile.open(fileobj=io.BytesIO(gzip.decompress(blob)), mode='r:') as guest:
        for name in ('install-guest-tools.sh', 'terminal-plus-guest.service', 'terminal-plus-guest.py', 'terminal-plus-capture.py', 'terminal-plus-proxy.py'):
            assert guest.extractfile(name).read() == Path('app/src/main/assets/guest-setup', name).read_bytes(), name
    assert archive.read('assets/bootloader/u-boot.bin') == Path('app/src/main/assets/bootloader/u-boot.bin').read_bytes()
    assert archive.read('assets/guest-tools.iso') == Path('app/src/main/assets/guest-tools.iso').read_bytes()
    manifest = archive.read('assets/guest-tools-manifest.json')
    assert manifest == Path('app/src/main/assets/guest-tools-manifest.json').read_bytes()
    for source, expected in json.loads(manifest).items():
        assert hashlib.sha256(Path(source).read_bytes()).hexdigest() == expected, 'Rebuild guest-tools.iso: ' + source
    executable = archive.read('lib/arm64-v8a/libqemu-img.so')
    for name in ('template.iso', 'layout.json', 'network-config'):
        assert archive.read('assets/cloud-init/' + name) == Path('app/src/main/assets/cloud-init', name).read_bytes()
    assert hashlib.sha256(executable).hexdigest() == Path('third_party/qemu-img/binary.sha256').read_text().strip()
    for notice in Path('third_party/qemu-img').rglob('*'):
        if notice.is_file():
            assert archive.read('assets/qemu-img/' + str(notice.relative_to('third_party/qemu-img'))) == notice.read_bytes()
print('APK runtime payload names, bytes and bootloader verified')
