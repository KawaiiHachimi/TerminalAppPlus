#!/usr/bin/env python3
"""Verify readable Rock Ridge installer paths, label and exact payload content."""
import importlib.util
from io import BytesIO
from pathlib import Path
import pycdlib

spec = importlib.util.spec_from_file_location('builder', Path(__file__).with_name('build-guest-tools-iso.py'))
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)
iso = pycdlib.PyCdlib()
iso.open(str(builder.assets / 'guest-tools.iso'))
assert iso.pvd.volume_identifier.rstrip() == b'PLUS_TOOLS'
for name, source in builder.sources.items():
    output = BytesIO()
    iso.get_file_from_iso_fp(output, rr_path='/' + name)
    assert output.getvalue() == source.read_bytes(), name
iso.close()
print('Guest Tools ISO label, installer and all service payloads verified')
