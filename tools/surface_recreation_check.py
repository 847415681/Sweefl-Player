"""Paused output survives Activity stop/restart and orientation Surface replacement."""
import time,os,json
from device_lab import OUT
import numpy as np
from PIL import Image
from device_lab import verify_device,launch,snapshot,adb
from ui_lab import ui,tap,find,screenshot
from glass_gesture_regression import bounds,record,show
verify_device()
def fresh(pred):
 end=time.monotonic()+20
 while time.monotonic()<end:
  s=snapshot()
  if pred(s):return s
  time.sleep(.2)
 raise AssertionError(s)
for mode,gpu in [('AUTO',False),('AUTO',True),('FFMPEG',False)]:
 if os.environ.get('SWEEFL_SURFACE_MODE') and mode!=os.environ['SWEEFL_SURFACE_MODE']:continue
 tag=mode+str(gpu)
 launch('synthetic/seek-epoch-rgb.mp4',mode=mode,extra=('--ez','keepControls','true','--ez','benchmark','false'),key='surface-'+tag)
 fresh(lambda s:s.get('renderedFrames',0)>3);tap(ui('pause'),'暂停');before=fresh(lambda s:not s['isPlaying'])
 assert before['positionMs']<9000
 if gpu:
  tap(ui('details'),'播放信息与解码');tap(show('画面与渲染设置','entry'),'画面与渲染设置');tap(show('GPU 增强（输出 SDR）','gpu'),'GPU 增强（输出 SDR）');tap(show('应用','apply'),'应用');t=ui('applied')
  if find(t,'完成') is not None:tap(t,'完成')
  fresh(lambda s:s.get('rendering',{}).get('operational'))
 for action in ('rotate','home'):
  if action=='rotate':tap(ui('rotate'),'切换横竖屏')
  else:
   adb('shell','input','keyevent','3');time.sleep(1)
   adb('shell','am','start','-W','-f','0x10000000','-n','com.sweefl.player/.PlayerActivity')
  fresh(lambda s:not s['isPlaying'] and s['playbackState']==3 and s.get('renderedFrames',0)>0)
  deadline=time.monotonic()+20
  while True:
   t=ui(tag+action);node=find(t,'播放')
   if node is not None:break
   if time.monotonic()>=deadline:
    screenshot(tag+action+'-not-ready')
    pid=adb('shell','pidof','com.sweefl.player').strip()
    (OUT/'failure-logcat.txt').write_text(adb('logcat','-d','--pid',pid),encoding='utf-8')
    (OUT/'failure-windows.txt').write_text(adb('shell','dumpsys','window','windows'),encoding='utf-8')
    (OUT/'failure-snapshot.json').write_text(json.dumps(snapshot(),indent=2))
    raise AssertionError({'case':tag+action,'controlsNotReady':snapshot()})
   time.sleep(.2)
  a,b,c,d=bounds(node)
  # A diagnostics file may still describe the released player immediately after am start.
  deadline=time.monotonic()+15
  while True:
   p=screenshot(tag+action);im=np.array(Image.open(p).convert('RGB')).astype(float)
   h,w=im.shape[:2];video=im[int(h*.43):int(h*.46),int(w*.40):int(w*.43)].mean((0,1))
   if video[0]-min(video)>80:break
   if time.monotonic()>=deadline:raise AssertionError({'case':tag+action,'primaryVideoNotRestored':video.tolist()})
   time.sleep(.2)
  glass=im[int(b+(d-b)*.22):int(b+(d-b)*.34),int(a+(c-a)*.35):int(a+(c-a)*.65)].mean((0,1))
  record(tag+action+' paused red glass restored',glass[0]-min(glass)>50,glass.tolist())
  s=snapshot();record(tag+action+' remains paused',not s['isPlaying'],s['positionMs'])
print('Surface recreation checks passed',flush=True)
