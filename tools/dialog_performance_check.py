"""Same-device foreground UI frame stats without screenrecord overhead; run under guard."""
import time,json
from device_lab import adb,launch,OUT,verify_device
from ui_lab import ui,tap,find,screenshot
verify_device()
launch('synthetic/avc1080.mp4',extra=('--ez','keepControls','true'),key='dialog-perf')
time.sleep(1)
t=ui('initial')
if find(t,'播放信息与解码') is None:
 adb('shell','input','tap','540','500');t=ui('reveal')
tap(t,'播放信息与解码');ui('dialog');time.sleep(1)
adb('shell','dumpsys','gfxinfo','com.sweefl.player','reset')
start=time.monotonic();time.sleep(12)
(OUT/'dialog-gfxinfo.txt').write_text(adb('shell','dumpsys','gfxinfo','com.sweefl.player','framestats'),encoding='utf-8')
(OUT/'dialog-memory.txt').write_text(adb('shell','dumpsys','meminfo','com.sweefl.player'),encoding='utf-8')
screenshot('dialog-playing')
assert adb('shell','pidof','com.sweefl.player').strip()
(OUT/'measurement.json').write_text(json.dumps({'durationSeconds':time.monotonic()-start,'note':'gfxinfo UI frames, not decoder dropped-frame count; no recorder during measurement'},indent=2))
print('Captured dialog UI frame stats and memory',flush=True)
