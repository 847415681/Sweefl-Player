"""Run against parallel_fixture_server under preference guard; verifies actual decoder progress."""
import time,json
from device_lab import launch,snapshot,OUT,verify_device
verify_device();results=[]
for path in ('range.mp4','ignore.mp4','weak.mp4'):
 start=time.monotonic();launch(path,key='parallel-check')
 deadline=time.monotonic()+25
 while time.monotonic()<deadline:
  s=snapshot()
  if s.get('error'):raise AssertionError(s)
  if s.get('renderedFrames',0)>15 and s.get('isPlaying'):break
  time.sleep(.2)
 else:raise AssertionError(s)
 results.append({'path':path,'elapsedSeconds':time.monotonic()-start,'state':s});print(path,'plays',flush=True)
 time.sleep(3)
(OUT/'results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
