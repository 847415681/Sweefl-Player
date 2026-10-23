"""Throttled local Range fixture with strong validator and active request telemetry."""
import argparse,json,re,time,threading,hashlib
from pathlib import Path
from http.server import ThreadingHTTPServer,BaseHTTPRequestHandler
from urllib.parse import urlsplit
p=argparse.ArgumentParser();p.add_argument('--file',required=True);p.add_argument('--log',required=True);a=p.parse_args()
file=Path(a.file);size=file.stat().st_size;tag='"'+hashlib.sha256(file.read_bytes()).hexdigest()+'"';log=Path(a.log);log.parent.mkdir(parents=True,exist_ok=True)
lock=threading.Lock();active=0
def record(data):
 with lock:
  with log.open('a',encoding='utf-8') as f:f.write(json.dumps(dict(time=time.time(),**data))+'\n')
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_GET(self):
  global active
  path=urlsplit(self.path).path;start=0;end=size-1;status=200
  if self.headers.get('User-Agent')!='Sweefl-QA/0.2':self.send_error(403);return
  if path not in ('/require-ua/range.mp4','/require-ua/ignore.mp4','/require-ua/weak.mp4'):self.send_error(404);return
  match=re.fullmatch(r'bytes=(\d+)-(\d*)',self.headers.get('Range',''))
  if match and 'ignore' not in path:
   start=int(match[1]);end=min(int(match[2]) if match[2] else end,end);status=206
  if start>=size or end<start:self.send_error(416);return
  if self.headers.get('If-Match') not in (None,tag):self.send_error(412);return
  with lock:active+=1;count=active
  record({'event':'start','path':path,'range':self.headers.get('Range'),'active':count,'status':status,'ifMatch':self.headers.get('If-Match')})
  sent=0
  try:
   self.send_response(status);self.send_header('Content-Type','video/mp4');self.send_header('ETag',('W/' if 'weak' in path else '')+tag);self.send_header('Content-Length',str(end-start+1));self.send_header('Accept-Ranges','bytes')
   if status==206:self.send_header('Content-Range',f'bytes {start}-{end}/{size}')
   self.end_headers()
   with file.open('rb') as f:
    f.seek(start);remaining=end-start+1
    while remaining:
     data=f.read(min(16384,remaining));self.wfile.write(data);self.wfile.flush();sent+=len(data);remaining-=len(data);time.sleep(.012)
  except (BrokenPipeError,ConnectionResetError,ConnectionAbortedError):pass
  finally:
   with lock:active-=1;count=active
   record({'event':'end','path':path,'bytes':sent,'active':count})
print('Parallel fixture listening on127.0.0.1:8765',flush=True)
ThreadingHTTPServer(('127.0.0.1',8765),Handler).serve_forever()
