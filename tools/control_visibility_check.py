"""Verify rendered icons, not just accessibility hit targets, with glass on/off and re-entry."""
import time
import numpy as np
from PIL import Image
from device_lab import verify_device,launch
from ui_lab import ui,tap,find,screenshot
from glass_gesture_regression import bounds,show,input_cmd,record
verify_device()
def start_paused(key):
 launch('synthetic/seek-epoch-rgb.mp4',extra=('--ez','benchmark','false'),key=key)
 # Allow the normal playing HUD to hide, reveal it, then pause without another slow dump.
 time.sleep(4)
 t=ui(key+'-bounds');a,b,c,d=bounds(next(t.iter('node')))
 input_cmd(f'tap {(a+c)//2} {(b+d)//2}');time.sleep(.45)
 input_cmd(f'tap {(a+c)//2} {(b+d)//2}')
 assert find(ui(key+'-paused'),'播放') is not None
def check_visible(tag):
 t=ui(tag);im=np.asarray(Image.open(screenshot(tag)).convert('RGB'))
 for label in ('返回媒体库','播放信息与解码','播放','手势与进度预览'):
  node=find(t,label);assert node is not None,(tag,label,'missing hit target')
  a,b,c,d=bounds(node);crop=im[b:d,a:c].astype(np.int16)
  bright=(crop.min(axis=2)>130)&(np.ptp(crop,axis=2)<110)
  record(tag+' '+label+' has actual visible icon pixels',int(bright.sum())>40,int(bright.sum()))
 return t
def switch_glass(expected):
 for attempt in range(3):
  t=ui('settings-entry-'+str(attempt))
  if find(t,'播放信息与解码') is not None:tap(t,'播放信息与解码')
  else:
   a,b,c,d=bounds(next(t.iter('node')));input_cmd(f'tap {(a+c)//2} {int(b+(d-b)*.3)}')
  t=ui('settings-open-'+str(attempt))
  if any(x.get('text')=='播放信息与解码' for x in t.iter('node')):break
 else:raise AssertionError('Settings dialog did not open')
 t=show('实时折射','glass-switch')
 n=next(x for x in t.iter('node') if x.get('checkable')=='true')
 if (n.get('checked')=='true')!=expected:
  a,b,c,d=bounds(n);input_cmd(f'tap {(a+c)//2} {(b+d)//2}')
 t=ui('switch-set');assert (next(x for x in t.iter('node') if x.get('checkable')=='true').get('checked')=='true')==expected
 tap(show('完成','done'),'完成')
start_paused('visibility-on')
check_visible('glass-on')
switch_glass(False)
check_visible('glass-off')
start_paused('visibility-off-reentry')
t=check_visible('glass-off-reentry')
switch_glass(True)
t=check_visible('glass-reenabled')
tap(t,'播放');assert find(ui('resume-works'),'暂停') is not None
print('Actual control pixels and interaction pass with glass on, off, re-entry and re-enabled',flush=True)
