"""Download immutable-version source inputs, preserving bytes; never execute archive content."""
from pathlib import Path
import concurrent.futures, hashlib, json, tarfile, urllib.request
BASE = Path(__file__).resolve().parent
COMMIT = '1b69e9490c172c42f73f7a6f1b9ffd1c8be4a060'
SOURCES = [
 ('nextlib-'+COMMIT+'.tar.gz', 'https://github.com/anilbeesetti/nextlib/archive/'+COMMIT+'.tar.gz'),
 ('ffmpeg-6.0.tar.gz', 'https://ffmpeg.org/releases/ffmpeg-6.0.tar.gz'),
 ('libvpx-v1.13.0.tar.gz', 'https://github.com/webmproject/libvpx/archive/refs/tags/v1.13.0.tar.gz'),
 ('mbedtls-v3.4.1.tar.gz', 'https://github.com/Mbed-TLS/mbedtls/archive/refs/tags/v3.4.1.tar.gz'),
]
def download(item):
 name, url = item
 dest = BASE/'archives'/name
 dest.parent.mkdir(parents=True, exist_ok=True)
 if not dest.exists():
  req = urllib.request.Request(url, headers={'User-Agent':'Sweefl-source-kit/0.3.2'})
  with urllib.request.urlopen(req, timeout=120) as response:
   data = response.read()
  with tarfile.open(fileobj=__import__('io').BytesIO(data), mode='r:gz') as archive:
   archive.getmembers()
  dest.write_bytes(data)
 data = dest.read_bytes()
 print(name, len(data), hashlib.sha256(data).hexdigest(), flush=True)
 return {'file':'archives/'+name,'requested_url':url,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}
if __name__ == '__main__':
 results=[]; errors=[]
 with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
  futures={pool.submit(download,item):item for item in SOURCES}
  for future in concurrent.futures.as_completed(futures):
   try: results.append(future.result())
   except Exception as exc:
    errors.append({'file':futures[future][0], 'error':str(exc)})
    print('ERROR', futures[future][0], str(exc), flush=True)
 (BASE/'download-provenance.json').write_text(json.dumps({'sources':sorted(results,key=lambda x:x['file']),'errors':errors},indent=2)+'\n',encoding='utf-8')
 if errors: raise SystemExit(1)
