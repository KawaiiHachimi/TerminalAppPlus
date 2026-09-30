#!/usr/bin/env python3
"""NoCloud ISO template with fixed, padded YAML payload slots (Rock Ridge names)."""
from pathlib import Path
from io import BytesIO
import json
import pycdlib
root = Path(__file__).resolve().parents[1]
assets = root / 'app/src/main/assets/cloud-init'
assets.mkdir(parents=True, exist_ok=True)
iso = pycdlib.PyCdlib()
iso.new(vol_ident='CIDATA', rock_ridge='1.09')
for name, ident, size in [('user-data', 'USER.;1', 65536), ('meta-data', 'META.;1', 4096)]:
    data = b'#' + b' ' * (size - 2) + b'\n'
    iso.add_fp(BytesIO(data), size, iso_path='/' + ident, rr_name=name, file_mode=0o100600)
# NoCloud reads networking separately, before user-data modules run.
network = (assets / 'network-config').read_bytes()
iso.add_fp(BytesIO(network), len(network), iso_path='/NETWORK.;1',
           rr_name='network-config', file_mode=0o100600)
p = assets / 'template.iso'
iso.write(str(p)); iso.close()
iso = pycdlib.PyCdlib(); iso.open(str(p))
layout = {}
for name in ['user-data', 'meta-data']:
    record = iso.get_record(rr_path='/' + name)
    layout[name] = {'offset': record.extent_location() * 2048, 'length': record.data_length}
iso.close()
(assets / 'layout.json').write_text(json.dumps(layout, indent=2) + '\n')
