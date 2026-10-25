"""Record real modal first frames and blur dragging under guarded_v032 (Debug or Release)."""
import subprocess,time,uuid
from device_lab import verify_device,launch,adb,ADB,SERIAL,OUT
from ui_lab import ui,tap,find,screenshot
from glass_gesture_regression import bounds,input_cmd
verify_device()

def prepare(key):
 # Release ignores keepControls. Pause through normal input before reading slow UI dumps,
 # then resume and open the modal back-to-back from known visible control positions.
 launch('synthetic/avc1080.mp4',extra=('--ez','benchmark','false'),key=key)
 time.sleep(4)
 t=ui(key+'-bounds');a,b,c,d=bounds(next(t.iter('node')))
 input_cmd(f'tap {(a+c)//2} {(b+d)//2}');time.sleep(.45)
 input_cmd(f'tap {(a+c)//2} {(b+d)//2}')
 t=ui(key+'-paused');assert find(t,'播放') is not None
 a,b,c,d=bounds(next(t.iter('node')))
 if c-a<d-b:
  tap(t,'切换横竖屏');t=ui(key+'-landscape')
 a,b,c,d=bounds(next(t.iter('node')))
 assert c-a>d-b and find(t,'播放') is not None
 return t

def record(name,action):
 remote='/sdcard/Download/Sweefl-modal-'+uuid.uuid4().hex+'.mp4'
 with (OUT/(name+'.log')).open('w') as log:
  proc=subprocess.Popen([ADB,'-s',SERIAL,'shell','screenrecord','--time-limit','12','--bit-rate','16000000',remote],stdout=log,stderr=log)
  try:
   time.sleep(.5);action();proc.wait(timeout=20)
  finally:
   if proc.poll() is None:proc.terminate();proc.wait(timeout=5)
 assert proc.returncode==0
 adb('pull',remote,str(OUT/(name+'.mp4')))
 adb('shell','rm',remote)

t=prepare('modal-info')
def open_info():
 tap(t,'播放');tap(t,'播放信息与解码')
 shown=ui('info-open');assert any(n.get('text')=='播放信息与解码' for n in shown.iter('node'))
 screenshot('info-open')
record('info-open',open_info)
for n in range(12):
 t=ui('blur-find-'+str(n));nodes=[x for x in t.iter('node') if x.get('content-desc')=='玻璃模糊程度' and x.get('class')=='android.widget.SeekBar']
 if nodes and bounds(nodes[0])[3]-bounds(nodes[0])[1]>=50:break
 s=next(x for x in t.iter('node') if x.get('scrollable')=='true');a,b,c,d=bounds(s)
 input_cmd(f'swipe {(a+c)//2} {int(b+.8*(d-b))} {(a+c)//2} {int(b+.3*(d-b))} 400')
else:raise AssertionError('Blur slider unavailable')
a,b,c,d=bounds(nodes[0]);y=(b+d)//2

def drag_blur():
 for _ in range(4):
  input_cmd(f'swipe {a+4} {y} {c-4} {y} 700')
  input_cmd(f'swipe {c-4} {y} {a+4} {y} 700')
 screenshot('after-drag')
record('blur-drag',drag_blur)
input_cmd('keyevent 4')
# A fresh moving fixture avoids mistaking the first fixture's ended frame for low FPS.
t=prepare('modal-gestures')
def open_gestures():
 tap(t,'播放');tap(t,'手势与进度预览')
 shown=ui('gestures-open');assert any(n.get('text')=='手势与进度预览' for n in shown.iter('node'))
 screenshot('gestures-open')
record('gestures-open',open_gestures)
print('Modal first appearance and blur dragging recorded for frame inspection',flush=True)
