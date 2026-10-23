"""Record real compositor frames during press, seek and glass/dialog reveal; outer guard restores state."""
import subprocess,time,uuid,json
from device_lab import verify_device,launch,adb,ADB,SERIAL,OUT,snapshot
from ui_lab import ui,tap,find
from glass_gesture_regression import bounds,input_cmd
verify_device()
launch('synthetic/seek-epoch-rgb.mp4',extra=('--ez','keepControls','true','--ez','benchmark','false'),key='transition')
time.sleep(1);t=ui('controls');tap(t,'暂停');t=ui('paused')
def xy(label):
 a,b,c,d=bounds(find(t,label));return (a+c)//2,(b+d)//2
play=xy('播放');seek=xy('快进 10 秒');details=xy('播放信息与解码')
remote='/sdcard/Download/Sweefl-transition-'+uuid.uuid4().hex+'.mp4'
events=[]
with (OUT/'screenrecord.log').open('w') as log:
 proc=subprocess.Popen([ADB,'-s',SERIAL,'shell','screenrecord','--bit-rate','12000000','--time-limit','16',remote],stdout=log,stderr=log)
 try:
  time.sleep(1);start=time.monotonic()
  for label,point in [('play-press',play),('pause-press',play),('seek-background',seek),('dialog-open',details)]:
   events.append({'event':label,'elapsedSeconds':time.monotonic()-start})
   input_cmd(f'tap {point[0]} {point[1]}');time.sleep(2)
  proc.wait(timeout=24)
 finally:
  if proc.poll() is None:proc.terminate();proc.wait(timeout=5)
assert proc.returncode==0,proc.returncode
adb('pull',remote,str(OUT/'transition.mp4'))
assert remote.startswith('/sdcard/Download/Sweefl-transition-') and len(remote.rsplit('-',1)[1])==36
assert (OUT/'transition.mp4').stat().st_size>0
adb('shell','rm',remote)
(OUT/'events.json').write_text(json.dumps(events,indent=2))
print('Captured transition video; requires frame analysis, not automatic visual pass.',flush=True)
