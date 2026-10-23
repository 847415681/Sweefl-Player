"""Package complete project/retained dependency sources, excluding local secrets/build/evidence caches."""
from pathlib import Path
import hashlib,json,zipfile
root=Path(__file__).resolve().parents[1]
out=root/'dist/Sweefl-Player-0.3.2-source-r40.zip'
assert not out.exists(), 'Refuse to overwrite an existing release source bundle'
skip={'.git','.gradle','.kotlin','.idea','build','dist','__pycache__'}
files=[]
for p in root.rglob('*'):
 if not p.is_file():continue
 rel=p.relative_to(root)
 if any(x in skip for x in rel.parts):continue
 if rel.parts[0]=='docs' and any(x.startswith('testing') for x in rel.parts[1:]):continue
 if p.name=='local.properties' or p.suffix.lower() in {'.keystore','.jks','.pyc','.apk','.rgb'}:continue
 files.append((p,rel))
manifest={str(rel).replace('\\','/'):hashlib.sha256(p.read_bytes()).hexdigest() for p,rel in files}
for required in ['LICENSE','app/build.gradle.kts','core/domain/build.gradle.kts','gradle/wrapper/gradle-wrapper.jar','gradle/libs.versions.toml','tools/guarded_v032.py']:
 assert required in manifest,required
out.parent.mkdir(exist_ok=True)
with zipfile.ZipFile(out,'x',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
 for p,rel in files:z.write(p,'Sweefl-Player-0.3.2/'+rel.as_posix())
 z.writestr('Sweefl-Player-0.3.2/SOURCE-MANIFEST.json',json.dumps(manifest,indent=2))
with zipfile.ZipFile(out) as z:assert z.testzip() is None
print(json.dumps({'file':str(out),'files':len(files),'bytes':out.stat().st_size,'sha256':hashlib.sha256(out.read_bytes()).hexdigest()},indent=2))
