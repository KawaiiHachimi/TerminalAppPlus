#!/usr/bin/env python3
"""Create the corresponding-source archive distributed beside signed APKs."""
import argparse
import hashlib
import json
from pathlib import Path
import tarfile
import urllib.request

root = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument('--cache', type=Path, default=root / 'artifacts/qemu-img-source')
parser.add_argument('--output', type=Path, default=root / 'dist/qemu-img-sources.tar.gz')
args = parser.parse_args()
args.cache.mkdir(parents=True, exist_ok=True)
manifest = json.loads((root / 'third_party/qemu-img/sources.json').read_text())
for name, entry in manifest.items():
    target = args.cache / name
    if not target.exists():
        temporary = target.with_suffix('.partial')
        with urllib.request.urlopen(entry['url'], timeout=120) as response, temporary.open('wb') as out:
            while chunk := response.read(1024 * 1024):
                out.write(chunk)
        temporary.replace(target)
    if hashlib.sha256(target.read_bytes()).hexdigest() != entry['sha256']:
        raise SystemExit('Source checksum mismatch: ' + name)
args.output.parent.mkdir(parents=True, exist_ok=True)
with tarfile.open(args.output, 'w:gz', compresslevel=1) as bundle:
    for name in manifest:
        bundle.add(args.cache / name, arcname='upstream/' + name)
    for path in ['tools/qemu-img', 'third_party/qemu-img']:
        bundle.add(root / path, arcname=path)
print(args.output)
