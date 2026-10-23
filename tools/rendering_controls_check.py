"""Committed GPU controls, paused state, persistence, and FFmpeg audio combination."""
import time
from device_lab import verify_device,launch,snapshot
from ui_lab import ui,tap,find,screenshot
from glass_gesture_regression import record,show,bounds,input_cmd
verify_device()
def fresh(pred,timeout=18):
 old=snapshot().get('elapsedRealtimeMs',0);end=time.monotonic()+timeout
 while time.monotonic()<end:
  s=snapshot()
  if s.get('elapsedRealtimeMs',0)>old and pred(s):return s
  time.sleep(.25)
 raise AssertionError(s)
def menu():
 tap(ui('details'),'播放信息与解码');tap(show('画面与渲染设置','entry'),'画面与渲染设置')
def apply():
 tap(show('应用','apply'),'应用');t=ui('after-apply')
 if find(t,'完成') is not None:tap(t,'完成')
def select(label):tap(show(label,'select'),label)
launch('synthetic/avc1080.mp4',mode='FFMPEG',extra=('--ez','keepControls','true','--ez','benchmark','false'),key='render-controls')
fresh(lambda s:s.get('renderedFrames',0)>5)
tap(ui('pause'),'暂停');before=fresh(lambda s:not s['isPlaying'])
menu();select('GPU 增强（输出 SDR）')
t=show('画面饱和度','saturation');n=find(t,'画面饱和度')
# Label and seekbar share content-description; choose actual semantic SeekBar explicitly.
sliders=[n for n in t.iter('node') if n.get('class')=='android.widget.SeekBar' and n.get('content-desc')=='画面饱和度']
if not sliders:
 area=next(n for n in t.iter('node') if n.get('scrollable')=='true')
 a,b,c,d=bounds(area)
 input_cmd(f'swipe {(a+c)//2} {int(b+(d-b)*.8)} {(a+c)//2} {int(b+(d-b)*.4)} 500')
 t=ui('saturation-scrolled')
 sliders=[n for n in t.iter('node') if n.get('class')=='android.widget.SeekBar' and n.get('content-desc')=='画面饱和度']
assert len(sliders)==1,'Saturation slider not reachable'
n=sliders[0];a,b,c,d=bounds(n)
input_cmd(f'motionevent DOWN {(a+c)//2} {(b+d)//2}');input_cmd(f'motionevent MOVE {a+1} {(b+d)//2}');input_cmd(f'motionevent UP {a+1} {(b+d)//2}')
apply();after=fresh(lambda s:s.get('rendering',{}).get('operational'))
record('paused commit preserves position speed and state',not after['isPlaying'] and abs(after['positionMs']-before['positionMs'])<5 and after['playbackSpeed']==before['playbackSpeed'],{'before':before,'after':after})
record('GPU excludes FFmpeg video while retaining audio','ffmpeg' not in (after.get('videoDecoder') or '').lower() and 'ffmpeg' in (after.get('audioDecoder') or '').lower(),after)
record('saturation control reaches minus100',after['rendering']['saturation']==-100,after['rendering'])
screenshot('saturation-minus100')
launch('synthetic/avc1080.mp4',mode='FFMPEG',extra=('--ez','keepControls','true','--ez','benchmark','false'),key='render-controls')
s=fresh(lambda s:s.get('rendering',{}).get('operational'))
record('GPU mode and color persist across process restart',s['rendering']['saturation']==-100,s['rendering'])
menu();select('画面参数归零');apply();s=fresh(lambda s:s.get('rendering',{}).get('operational'))
record('neutral reset committed',s['rendering']['saturation']==0,s['rendering'])
screenshot('saturation-reset')
print('Rendering controls passed',flush=True)
