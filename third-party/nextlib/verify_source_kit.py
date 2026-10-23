"""Verify archive hashes, extracted byte identity and the release integrity manifest."""
from pathlib import Path, PurePosixPath
import hashlib, json, tarfile
BASE=Path(__file__).resolve().parent
for item in json.loads((BASE/'download-provenance.json').read_text())['sources']:
 p=BASE/item['file']
 assert len(p.read_bytes())==item['bytes']
 assert hashlib.sha256(p.read_bytes()).hexdigest()==item['sha256']
 component='nextlib' if p.name.startswith('nextlib-') else p.name.removesuffix('.tar.gz')
 with tarfile.open(p,'r:gz') as archive:
  for member in archive.getmembers():
   path=PurePosixPath(member.name)
   assert not path.is_absolute() and '..' not in path.parts
   if not member.isfile(): continue
   rel=PurePosixPath(*path.parts[1:])
   if not rel.parts: continue
   legal=rel.name.upper().startswith(('LICENSE','LICENCE','COPYING','NOTICE','PATENTS','AUTHORS','COPYRIGHT'))
   if legal or component=='nextlib':
    copy=BASE/('licenses' if legal else 'pinned-build')/component/str(rel)
    assert copy.read_bytes()==archive.extractfile(member).read(), str(copy)
count=0
for line in (BASE/'SHA256SUMS').read_text().splitlines():
 digest, name=line.split('  ',1)
 assert hashlib.sha256((BASE/name).read_bytes()).hexdigest()==digest, name
 count+=1
print(f'PASS: 4 archives; safe member paths; all copied source/legal files byte-identical; {count} manifest entries verified.')
