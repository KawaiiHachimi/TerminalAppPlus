#!/usr/bin/env python3
"""Reproducibly overlay the original AOSP cidata with the non-root host port proxy.
Requires pycdlib (pip install pycdlib). Original file is preserved in guest/.
"""
from pathlib import Path
from io import BytesIO
import pycdlib

root = Path(__file__).resolve().parents[1]
# One source for the guest capture implementation; also package fixed upgrade payloads.
capture = (root/'tools/experiments/kms-capture-server.py').read_bytes()
(root/'guest/root_files/usr/local/bin/terminal-plus-capture.py').write_bytes(capture)
assets = root/'app/src/main/assets/guest-setup'
assets.mkdir(parents=True, exist_ok=True)
(assets/'terminal-plus-capture.py').write_bytes(capture)
(assets/'terminal-plus-capture.service').write_bytes((root/'guest/root_files/etc/systemd/system/terminal-plus-capture.service').read_bytes())
iso = pycdlib.PyCdlib()
iso.open(str(root / 'guest/cidata-aosp.iso'))
# AOSP ISO uses Rock Ridge filenames.
init = BytesIO()
iso.get_file_from_iso_fp(init, rr_path='/init.sh')
script = init.getvalue().replace(b'\t\tattach-cidata.service', b'\t\tterminal-plus-proxy.service\n\t\tterminal-plus-capture.service\n\t\tattach-cidata.service')
iso.rm_file(iso_path='/INIT.SH;1')
iso.add_fp(BytesIO(script), len(script), iso_path='/INIT.SH;1', rr_name='init.sh', file_mode=0o100755)
files = [
    ('root_files/usr/local/bin/terminal-plus-capture.py', '/CAPTURE.PY;1', 0o100755),
    ('root_files/etc/systemd/system/terminal-plus-capture.service', '/CAPTURE.SER;1', 0o100644),
    ('root_files/usr/local/bin/terminal-plus-proxy.py', '/ROOT_FIL/USR/LOCAL/BIN/PLUS.PY;1', 0o100755),
    ('root_files/etc/systemd/system/terminal-plus-proxy.service', '/ROOT_FIL/ETC/SYSTEMD/SYSTEM/PLUS.SER;1', 0o100644),
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
