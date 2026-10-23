"""Paused output survives Activity stop/restart and orientation Surface replacement."""
import time
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
  t=ui(tag+action);node=find(t,'播放');assert node is not None
  a,b,c,d=bounds(node);p=screenshot(tag+action);im=np.array(Image.open(p).convert('RGB')).astype(float)
  glass=im[int(b+(d-b)*.22):int(b+(d-b)*.34),int(a+(c-a)*.35):int(a+(c-a)*.65)].mean((0,1))
  record(tag+action+' paused red glass restored',glass[0]-min(glass)>50,glass.tolist())
  s=snapshot();record(tag+action+' remains paused',not s['isPlaying'],s['positionMs'])
print('Surface recreation checks passed',flush=True)
