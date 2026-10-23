"""Actual pixels for brightness/contrast extremes on a paused frame, under outer state guard."""
import time,json
import numpy as np
from PIL import Image
from device_lab import verify_device,launch,snapshot,OUT
from ui_lab import ui,tap,find,screenshot
from glass_gesture_regression import record,show,bounds,input_cmd
verify_device()
def fresh(pred):
 old=snapshot()['elapsedRealtimeMs'];end=time.monotonic()+20
 while time.monotonic()<end:
  s=snapshot()
  if s['elapsedRealtimeMs']>old and pred(s):return s
  time.sleep(.25)
 raise AssertionError(s)
def menu():
 tap(ui('details'),'播放信息与解码');tap(show('画面与渲染设置','entry'),'画面与渲染设置')
def apply():
 tap(show('应用','apply'),'应用');t=ui('applied')
 if find(t,'完成') is not None:tap(t,'完成')
 return fresh(lambda s:s.get('rendering',{}).get('operational'))
def slide(label,right):
 t=ui('slider-'+label)
 n=next(n for n in t.iter('node') if n.get('class')=='android.widget.SeekBar' and n.get('content-desc')==label)
 a,b,c,d=bounds(n);x=c-1 if right else a+1;y=(b+d)//2
 input_cmd(f'motionevent DOWN {(a+c)//2} {y}');input_cmd(f'motionevent MOVE {x} {y}');input_cmd(f'motionevent UP {x} {y}')
def pixels(name):
 screenshot(name)
 a=np.array(Image.open(OUT/'screenshots'/f'{name}.png').convert('RGB'))
 h,w=a.shape[:2]
 # Upper strip of centered 16:9 video, excluding controls, timestamp and letterbox.
 top=(h-w*9/16)/2
 roi=a[int(top+40):int(top+140),int(w*.1):int(w*.9)].astype(float)
 return {'mean':float(roi.mean()),'spread':float(roi.std()),'roi':[int(w*.1),int(top+40),int(w*.9),int(top+140)]}
launch('synthetic/avc1080.mp4',extra=('--ez','keepControls','true','--ez','benchmark','false'),key='color-extremes')
fresh(lambda s:s.get('renderedFrames',0)>5);tap(ui('pause'),'暂停');before=fresh(lambda s:not s['isPlaying'])
results={}
for label,key,right,expected in [('画面亮度','brightness',True,1),('画面亮度','brightness',False,-1),('画面对比度','contrast',False,-1)]:
 menu()
 if snapshot()['rendering']['requestedMode']!='GPU_SDR':tap(show('GPU 增强（输出 SDR）','gpu'),'GPU 增强（输出 SDR）')
 if key=='brightness':slide(label,right)
 if key=='contrast':
  # Brightness back to exact midpoint before testing contrast.
  t=ui('neutral-brightness');n=next(n for n in t.iter('node') if n.get('content-desc')=='画面亮度' and n.get('class')=='android.widget.SeekBar');a,b,c,d=bounds(n)
  input_cmd(f'tap {(a+c)//2} {(b+d)//2}')
  slide(label,right)
 s=apply();name=key+str(expected);results[name]=pixels(name)
 record(name+' setting committed',s['rendering'][key]==expected,s['rendering'])
 record(name+' pause and position retained',not s['isPlaying'] and s['positionMs']==before['positionMs'],s)
record('brightness maximum makes video white',results['brightness1']['mean']>245,results)
record('brightness minimum makes video black',results['brightness-1']['mean']<10,results)
record('contrast minimum removes spatial contrast',results['contrast-1']['spread']<3,results)
(OUT/'pixel-check.json').write_text(json.dumps(results,indent=2))
print('Rendering extremes passed',flush=True)
