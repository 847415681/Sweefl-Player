"""Paused RGB all-intra seek regression; sampled Surface+glass consistency, not latch proof."""
import time,json
import numpy as np
from PIL import Image
from device_lab import verify_device,launch,snapshot,OUT
from ui_lab import ui,tap,screenshot,find
from glass_gesture_regression import record,show,bounds
verify_device()
def fresh(pred):
 end=time.monotonic()+20
 while time.monotonic()<end:
  try:s=snapshot()
  except Exception:time.sleep(.2);continue
  if pred(s):return s
  time.sleep(.2)
 raise AssertionError(s)
results=[]
for mode,gpu in [('AUTO',False),('AUTO',True),('FFMPEG',False)]:
 tag=mode+('-gpu' if gpu else '-native')
 launch('synthetic/seek-epoch-rgb.mp4',mode=mode,extra=('--ez','keepControls','true','--ez','benchmark','false'),key='seek-epoch-'+tag)
 fresh(lambda s:s.get('renderedFrames',0)>3);t=ui(tag+'-pause');tap(t,'暂停')
 s=fresh(lambda s:not s['isPlaying']);assert s['positionMs']<9000,s
 if gpu:
  tap(ui(tag+'-details'),'播放信息与解码');tap(show('画面与渲染设置','entry'),'画面与渲染设置')
  tap(show('GPU 增强（输出 SDR）','gpu'),'GPU 增强（输出 SDR）');tap(show('应用','apply'),'应用')
  t=ui('applied')
  if find(t,'完成') is not None:tap(t,'完成')
  fresh(lambda s:s.get('rendering',{}).get('operational'))
 t=ui(tag+'-controls');play=find(t,'播放');assert play is not None
 a,b,c,d=bounds(play)
 for step in range(3):
  # Sample strip inside center glass, above the play glyph; reference outside glass.
  p=screenshot(tag+'-'+str(step));im=np.array(Image.open(p).convert('RGB')).astype(float)
  glass=im[int(b+(d-b)*.22):int(b+(d-b)*.34),int(a+(c-a)*.35):int(a+(c-a)*.65)].mean((0,1))
  h,w=im.shape[:2];reference=im[int(h*.43):int(h*.45),int(w*.05):int(w*.15)].mean((0,1))
  expected=step
  record(tag+f' step{step} video color',int(reference.argmax())==expected and reference[expected]-min(reference)>50,reference.tolist())
  record(tag+f' step{step} glass current color',int(glass.argmax())==expected and glass[expected]-min(glass)>15,glass.tolist())
  results.append({'mode':tag,'step':step,'video':reference.tolist(),'glass':glass.tolist(),'position':snapshot()['positionMs']})
  if step<2:
   old=snapshot()['positionMs'];tap(t,'快进 10 秒')
   fresh(lambda s:not s['isPlaying'] and s['positionMs']>=old+9900)
   time.sleep(.15)
 # Exercise queued seeks without waiting for intermediate screenshots.
 t=ui(tag+'-rapid');back=find(t,'后退 10 秒');forward=find(t,'快进 10 秒')
 if back is None:back=find(t,'快退 10 秒')
 assert back is not None and forward is not None
 from glass_gesture_regression import input_cmd
 x1,y1,x2,y2=bounds(back);u1,v1,u2,v2=bounds(forward)
 input_cmd(f'tap {(x1+x2)//2} {(y1+y2)//2}; input tap {(x1+x2)//2} {(y1+y2)//2}; input tap {(u1+u2)//2} {(v1+v2)//2}')
 fresh(lambda s:not s['isPlaying'] and 10000<=s['positionMs']<20000)
 p=screenshot(tag+'-rapid-final');im=np.array(Image.open(p).convert('RGB')).astype(float)
 glass=im[int(b+(d-b)*.22):int(b+(d-b)*.34),int(a+(c-a)*.35):int(a+(c-a)*.65)].mean((0,1))
 record(tag+' rapid seeks converge green',glass[1]-min(glass)>50,glass.tolist())
(OUT/'rgb-check.json').write_text(json.dumps(results,indent=2))
print('Paused RGB glass seeks passed',flush=True)
