"""Copy legal texts and Nextlib regular files; never extractall or create archive symlinks."""
from pathlib import Path, PurePosixPath
import tarfile
BASE=Path(__file__).resolve().parent
for source in sorted((BASE/'archives').glob('*.tar.gz')):
 component = 'nextlib' if source.name.startswith('nextlib-') else source.name.removesuffix('.tar.gz')
 with tarfile.open(source,'r:gz') as archive:
  for member in archive.getmembers():
   path=PurePosixPath(member.name)
   if path.is_absolute() or '..' in path.parts: raise ValueError('unsafe archive path')
   if not member.isfile(): continue
   rel=PurePosixPath(*path.parts[1:])
   if not rel.parts: continue
   name=rel.name.upper()
   legal = name.startswith(('LICENSE','LICENCE','COPYING','NOTICE','PATENTS','AUTHORS','COPYRIGHT'))
   build = component=='nextlib' and (rel.suffix in {'.sh','.patch','.kts','.toml','.properties'} or rel.name in {'CMakeLists.txt','README.md','gradlew','gradlew.bat'})
   if legal or build or component=='nextlib':
    dest=BASE/('licenses' if legal else 'pinned-build')/component/str(rel)
    if member.size>2_000_000: raise ValueError('unexpected large text')
    data=archive.extractfile(member).read()
    dest.parent.mkdir(parents=True,exist_ok=True)
    if dest.exists() and dest.read_bytes()!=data: raise ValueError('refuse replacement: '+str(dest))
    dest.write_bytes(data)
    print(dest.relative_to(BASE))
