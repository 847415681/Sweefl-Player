"""Local-only fixture server with Range and UA enforcement; reached by adb reverse."""
import argparse, json, mimetypes, re, time
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
from urllib.parse import unquote, urlsplit

p=argparse.ArgumentParser(); p.add_argument('--root',required=True); p.add_argument('--log',required=True); p.add_argument('--port',type=int,default=8765); p.add_argument('--chunk-delay',type=float,default=0); p.add_argument('--seek-delay',type=float,default=0); a=p.parse_args()
root=Path(a.root).resolve(); log=Path(a.log); log.parent.mkdir(parents=True,exist_ok=True)
class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def do_HEAD(self): self.serve(False)
    def do_GET(self): self.serve(True)
    def serve(self,body):
        path=unquote(urlsplit(self.path).path)
        ua=self.headers.get('User-Agent','')
        with log.open('a',encoding='utf-8') as f: f.write(json.dumps({'time':time.time(),'path':path,'ua':ua,'range':self.headers.get('Range')})+'\n')
        if path.startswith('/redirect/'):
            self.send_response(302); self.send_header('Location','/require-ua/'+path[len('/redirect/'):]); self.end_headers(); return
        if path.startswith('/require-ua/'):
            if ua!='Sweefl-QA/0.2': self.send_error(403,'Expected custom User-Agent'); return
            path=path[len('/require-ua/'):]
        file=(root/path.lstrip('/')).resolve()
        if not file.is_relative_to(root) or not file.is_file(): self.send_error(404); return
        size=file.stat().st_size; start=0; end=size-1; status=200
        range_value=self.headers.get('Range','')
        match=re.fullmatch(r'bytes=(\d+)-(\d*)',range_value)
        if match:
            start=int(match[1]); end=min(int(match[2]) if match[2] else end,end); status=206
            if start>=size or end<start: self.send_error(416); return
        if start > 0 and a.seek_delay > 0: time.sleep(a.seek_delay)
        self.send_response(status)
        mime={'.m3u8':'application/vnd.apple.mpegurl','.mpd':'application/dash+xml','.ts':'video/mp2t','.m4s':'video/iso.segment'}.get(file.suffix,mimetypes.guess_type(file)[0] or 'application/octet-stream')
        self.send_header('Content-Type',mime); self.send_header('Accept-Ranges','bytes'); self.send_header('Content-Length',str(end-start+1))
        if status==206: self.send_header('Content-Range',f'bytes {start}-{end}/{size}')
        self.end_headers()
        if body:
            try:
                with file.open('rb') as f:
                    f.seek(start); remaining=end-start+1
                    while remaining:
                        chunk=f.read(min(262144,remaining))
                        if not chunk: break
                        self.wfile.write(chunk); self.wfile.flush(); remaining-=len(chunk)
                        if a.chunk_delay > 0: time.sleep(a.chunk_delay)
            except (BrokenPipeError,ConnectionResetError,ConnectionAbortedError): pass
print(f'Fixture server 127.0.0.1:{a.port}',flush=True)
ThreadingHTTPServer(('127.0.0.1',a.port),Handler).serve_forever()
