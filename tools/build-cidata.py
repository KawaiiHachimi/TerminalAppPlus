#!/usr/bin/env python3
"""Reproducibly overlay the original AOSP cidata with the non-root host port proxy.
Requires pycdlib (pip install pycdlib). Original file is preserved in guest/.
"""
from pathlib import Path
from io import BytesIO
import pycdlib
import tarfile
import gzip

root = Path(__file__).resolve().parents[1]
# One source for the guest capture implementation; also package fixed upgrade payloads.
capture = (root/'tools/experiments/kms-capture-server.py').read_bytes()
(root/'guest/root_files/usr/local/bin/terminal-plus-capture.py').write_bytes(capture)
assets = root/'app/src/main/assets/guest-setup'
assets.mkdir(parents=True, exist_ok=True)
for name in ('guest', 'capture', 'proxy'):
    (assets/f'terminal-plus-{name}.py').write_bytes((root/f'guest/root_files/usr/local/bin/terminal-plus-{name}.py').read_bytes())
(assets/'terminal-plus-guest.service').write_bytes((root/'guest/root_files/etc/systemd/system/terminal-plus-guest.service').read_bytes())
(assets/'install-guest-tools.sh').write_bytes((root/'tools/install-guest-tools.sh').read_bytes())
# Deterministic bundle for guest service installation and upgrades.
bundle = BytesIO()
with tarfile.open(fileobj=bundle, mode='w') as archive:
    for name in ('terminal-plus-guest.py', 'terminal-plus-capture.py', 'terminal-plus-proxy.py', 'terminal-plus-guest.service', 'install-guest-tools.sh'):
        data = (assets/name).read_bytes()
        info = tarfile.TarInfo(name)
        info.size = len(data)
        info.mode = 0o644
        archive.addfile(info, BytesIO(data))
(assets/'guest-tools.bundle').write_bytes(gzip.compress(bundle.getvalue(), mtime=0))
iso = pycdlib.PyCdlib()
iso.open(str(root / 'guest/cidata-aosp.iso'))
# AOSP ISO uses Rock Ridge filenames.
init = BytesIO()
iso.get_file_from_iso_fp(init, rr_path='/init.sh')
script = init.getvalue().replace(b'\t\tattach-cidata.service', b'\t\tterminal-plus-guest.service\n\t\tattach-cidata.service')
iso.rm_file(iso_path='/INIT.SH;1')
iso.add_fp(BytesIO(script), len(script), iso_path='/INIT.SH;1', rr_name='init.sh', file_mode=0o100755)
files = [
    ('root_files/usr/local/bin/terminal-plus-capture.py', '/CAPTURE.PY;1', 0o100755),
    ('root_files/usr/local/bin/terminal-plus-guest.py', '/GUEST.PY;1', 0o100755),
    ('root_files/etc/systemd/system/terminal-plus-guest.service', '/GUEST.SER;1', 0o100644),
    ('root_files/usr/local/bin/terminal-plus-proxy.py', '/ROOT_FIL/USR/LOCAL/BIN/PLUS.PY;1', 0o100755),
]
for relative, iso_path, mode in files:
    # Resolve actual ISO parent identifiers from original Rock Ridge tree.
    parent_rr = '/' + str(Path(relative).parent)
    parent = iso.get_record(rr_path=parent_rr)
    components = []
    while parent.parent is not None:
        components.append(parent.file_identifier().decode())
        parent = parent.parent
    iso_path = '/' + '/'.join(reversed(components)) + '/' + iso_path.rsplit('/', 1)[1]
    iso.add_file(str(root/'guest'/relative), iso_path=iso_path,
                 rr_name=Path(relative).name, file_mode=mode)
iso.write(str(root/'app/src/main/assets/cidata.iso'))
iso.close()
(root/'app/src/main/assets/cidata.build_id').write_text('15101902-plus-display1\n')
