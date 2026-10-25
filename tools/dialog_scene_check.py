"""UI-only modal pause, sub-settings switching and reopen/rotation checks under guard."""
import json,re,time
import numpy as np
from PIL import Image
from device_lab import launch,verify_device,adb,OUT
from ui_lab import ui,tap,find,screenshot
from glass_gesture_regression import bounds,input_cmd,show
verify_device()
launch('synthetic/seek-epoch-rgb.mp4',extra=('--ez','benchmark','false'),key='modal-scene')
time.sleep(4)
t=ui('bounds');a,b,c,d=bounds(next(t.iter('node')))
input_cmd(f'tap {(a+c)//2} {(b+d)//2}');time.sleep(.45)
input_cmd(f'tap {(a+c)//2} {(b+d)//2}')
t=ui('paused');assert find(t,'播放') is not None
position=next(n.get('text') for n in t.iter('node') if re.fullmatch(r'\d+:\d\d',n.get('text','')))
def color(name):
 im=np.asarray(Image.open(screenshot(name)).convert('RGB'));h,w,_=im.shape
 return np.median(im[int(h*.43):int(h*.46),int(w*.40):int(w*.43)],axis=(0,1))
base=color('underlay');assert np.ptp(base)>100
results=[]
def check_color(name):
 value=color(name);assert np.ptp(value)>25 and value.argmax()==base.argmax(),(name,value,base)
 results.append({'scene':name,'color':value.tolist()})
tap(t,'播放信息与解码');ui('info');check_color('info')
time.sleep(1)
adb('shell','dumpsys','gfxinfo','com.sweefl.player','reset');time.sleep(3)
paused_stats=adb('shell','dumpsys','gfxinfo','com.sweefl.player','framestats')
(OUT/'paused-gfxinfo.txt').write_text(paused_stats,encoding='utf-8')
frames=int(re.search(r'Total frames rendered: (\d+)',paused_stats)[1])
assert frames<20,('Paused scene unexpectedly redraws continuously',frames)
for attempt in range(12):
 t=ui('paused-blur-find-'+str(attempt))
 sliders=[n for n in t.iter('node') if n.get('content-desc')=='玻璃模糊程度' and n.get('class')=='android.widget.SeekBar' and bounds(n)[3]-bounds(n)[1]>=50]
 if sliders:break
 scroll=next(n for n in t.iter('node') if n.get('scrollable')=='true');a,b,c,d=bounds(scroll)
 input_cmd(f'swipe {(a+c)//2} {int(b+.8*(d-b))} {(a+c)//2} {int(b+.3*(d-b))} 400')
else:raise AssertionError('Paused blur slider unavailable')
a,b,c,d=bounds(sliders[0])
for name,x in [('paused-max-blur',c-4),('paused-zero-blur',a+4),('paused-max-again',c-4)]:
 input_cmd(f'tap {x} {(b+d)//2}');time.sleep(.3);check_color(name)
tap(show('画面与渲染设置','nested-entry'),'画面与渲染设置');ui('nested');check_color('nested')
input_cmd('keyevent 4');ui('parent-return');check_color('parent-return')
input_cmd('keyevent 4');t=ui('closed');assert find(t,'播放') is not None
assert any(n.get('text')==position for n in t.iter('node'))
tap(t,'切换横竖屏');t=ui('rotated');assert find(t,'播放') is not None
tap(t,'播放信息与解码');ui('reopened');check_color('rotated-reopen')
input_cmd('keyevent 4');t=ui('final-paused');assert find(t,'播放') is not None
assert any(n.get('text')==position for n in t.iter('node'))
(OUT/'scene-check.json').write_text(json.dumps({'position':position,'pausedDraws':frames,'scenes':results},indent=2),encoding='utf-8')
print('Sub-settings optics, static paused scene, rotation/reopen and preserved pause position passed',flush=True)
