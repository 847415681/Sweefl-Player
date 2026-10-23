"""Source-aware performance evidence; never demand 120 copies from 30fps content."""
import json,re,time
from device_lab import verify_device,launch,adb,OUT
from glass_motion_baseline import measure
from glass_gesture_regression import record
verify_device()
launch('synthetic/avc1080.mp4',extra=('--ez','keepControls','true'),key='source-cadence')
data=measure('source-cadence',seconds=10)
rate=data['primaryRenderedFrames']/data['seconds'];drops=data['primaryDroppedFrames'];total=data['primaryRenderedFrames']+drops
record('primary 30fps source sustains playback',rate>=25,{'actualRenderedFps':rate,'dropped':drops})
record('primary drop ratio stays below five percent',drops/max(1,total)<.05,data)
# The logger is global; retain only the latest ten-second sample window after warmup.
log=(OUT/'source-cadence-glass.log').read_text(encoding='utf-8')
rows=[]
for line in log.splitlines():
 m=re.search(r'playing=true samples=(\d+) seconds=([\d.]+) fps=([\d.]+) copyMeanMs=([\d.]+) copyMaxMs=([\d.]+)',line)
 if m:rows.append(dict(zip(('samples','seconds','fps','copyMeanMs','copyMaxMs'),map(float,m.groups()))))
rows=rows[-4:]
record('glass capture logs exist',bool(rows),rows)
record('30fps source is not repeatedly copied at display cadence',all(r['fps']<=36 for r in rows),rows)
(OUT/'cadence-result.json').write_text(json.dumps({'primaryRenderedFps':rate,'dropRatio':drops/max(1,total),'glass':rows,'note':'30fps fixture. Not a 60/90/120fps UI smoothness certification.'},indent=2))
