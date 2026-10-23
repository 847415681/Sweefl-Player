"""Release visual capture at blur0/50; no diagnostic hooks or fixed display-rate claims."""
import subprocess,time,uuid
from device_lab import verify_device,launch,adb,ADB,SERIAL,OUT
from ui_lab import ui,tap,find,screenshot
from glass_gesture_regression import bounds,input_cmd
verify_device()
def controls(label):
 t=ui(label)
 if find(t,'播放信息与解码') is None:
  input_cmd('tap 540 500');t=ui(label+'-reveal')
 return t
for amount in (0,50):
 launch('synthetic/seek-epoch-rgb.mp4',key='final-visual-'+str(amount))
 t=controls('start'+str(amount));tap(t,'播放信息与解码')
 for n in range(12):
  t=ui('blur'+str(amount)+'-'+str(n));nodes=[x for x in t.iter('node') if x.get('content-desc')=='玻璃模糊程度' and x.get('class')=='android.widget.SeekBar']
  if nodes:
   a,b,c,d=bounds(nodes[0])
   if d-b>=50:
    input_cmd(f'tap {a+2 if amount==0 else (a+c)//2} {(b+d)//2}');break
  s=next(x for x in t.iter('node') if x.get('scrollable')=='true');a,b,c,d=bounds(s)
  input_cmd(f'swipe {(a+c)//2} {int(b+.8*(d-b))} {(a+c)//2} {int(b+.3*(d-b))} 400')
 else:raise AssertionError('blur slider missing')
 t=ui('blur-confirm'+str(amount));assert find(t,str(amount)+'%') is not None
 input_cmd('keyevent 4');t=controls('ready'+str(amount));tap(t,'切换横竖屏');t=controls('landscape'+str(amount))
 # Pause before recording, then queue repeated seeks and reveal without test-only keepControls.
 for attempt in range(5):
  if find(t,'播放') is not None:break
  if find(t,'暂停') is not None:tap(t,'暂停')
  t=controls('pause-attempt-'+str(amount)+'-'+str(attempt))
 assert find(t,'播放') is not None,'Must pause before transition capture'
 # Start at 5s, away from natural color boundaries and EOF.
 slider=next(x for x in t.iter('node') if x.get('class')=='android.widget.SeekBar')
 a,b,c,d=bounds(slider);input_cmd(f'tap {int(a+(c-a)/6)} {(b+d)//2}')
 time.sleep(.8);t=controls('paused'+str(amount));assert find(t,'播放') is not None
 points={}
 for label in ('快进 10 秒','后退 10 秒'):
  a,b,c,d=bounds(find(t,label));points[label]=((a+c)//2,(b+d)//2)
 remote='/sdcard/Download/Sweefl-final-'+uuid.uuid4().hex+'.mp4'
 with (OUT/('record-'+str(amount)+'.log')).open('w') as log:
  proc=subprocess.Popen([ADB,'-s',SERIAL,'shell','screenrecord','--time-limit','12','--bit-rate','16000000',remote],stdout=log,stderr=log)
  time.sleep(1)
  for label in ('快进 10 秒','后退 10 秒')*4:
   x,y=points[label];input_cmd(f'tap {x} {y}');time.sleep(.4)
  proc.wait(timeout=20)
 assert proc.returncode==0
 local=OUT/('blur-'+str(amount)+'.mp4');adb('pull',remote,str(local));assert local.stat().st_size>0
 assert remote.startswith('/sdcard/Download/Sweefl-final-') and len(remote.rsplit('-',1)[1])==36
 adb('shell','rm',remote);screenshot('final-'+str(amount))
 print('Captured release repeated seek transitions blur '+str(amount),flush=True)
