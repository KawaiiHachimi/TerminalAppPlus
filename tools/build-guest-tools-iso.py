#!/usr/bin/env python3
"""Build the standalone custom-VM tools disk. Requires pycdlib; no AOSP init."""
from io import BytesIO
from pathlib import Path
import pycdlib
import hashlib
import json

root = Path(__file__).resolve().parents[1]
assets = root / 'app/src/main/assets'
sources = {
    'install.sh': root / 'tools/install-custom-guest.sh',
    'install-guest-tools.sh': root / 'tools/install-guest-tools.sh',
    'terminal-plus-guest.service': root / 'guest/root_files/etc/systemd/system/terminal-plus-guest.service',
    'terminal-plus-guest.py': root / 'guest/root_files/usr/local/bin/terminal-plus-guest.py',
    'terminal-plus-proxy.py': root / 'guest/root_files/usr/local/bin/terminal-plus-proxy.py',
    'terminal-plus-capture.py': root / 'tools/experiments/kms-capture-server.py',
}

def build():
    iso = pycdlib.PyCdlib()
    iso.new(vol_ident='PLUS_TOOLS', rock_ridge='1.09')
    for i, (name, path) in enumerate(sources.items()):
        data = path.read_bytes()
        iso.add_fp(BytesIO(data), len(data), iso_path=f'/FILE{i:02};1', rr_name=name,
                   file_mode=0o100644)
    iso.write(str(assets / 'guest-tools.iso'))
    iso.close()
    manifest = {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
                for path in sources.values()}
    manifest['app/src/main/assets/guest-tools.iso'] = hashlib.sha256(
        (assets / 'guest-tools.iso').read_bytes()).hexdigest()
    (assets / 'guest-tools-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')

if __name__ == '__main__':
    build()
