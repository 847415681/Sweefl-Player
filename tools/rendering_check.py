"""Guarded on-device evidence for actual SDR GPU graph, not display colorimetry."""
import time
from device_lab import verify_device,launch,snapshot
from ui_lab import ui,tap,find,screenshot
from glass_gesture_regression import record,show
verify_device()
def fresh(predicate,timeout=18):
 start=snapshot().get('elapsedRealtimeMs',0);end=time.monotonic()+timeout
 while time.monotonic()<end:
  s=snapshot()
  if s.get('elapsedRealtimeMs',0)>start and predicate(s):return s
  time.sleep(.25)
 raise AssertionError(s)
def configure(mode):
 tap(ui('before-details'),'播放信息与解码')
 tap(show('画面与渲染设置','render-settings-entry'),'画面与渲染设置')
 t=show(mode,'render-mode');tap(t,mode)
 tap(show('应用','apply-rendering'),'应用')
 t=ui('after-render-settings')
 if find(t,'完成') is not None:tap(t,'完成')
for name,path in [('SDR','synthetic/avc1080.mp4'),('PQ','media3/mp4__hdr10-720p.mp4'),('HLG','media3/mp4__hlg-1080p.mp4')]:
 launch(path,extra=('--ez','keepControls','true'),key='render-'+name)
 s=fresh(lambda s:s['playbackState']==3 and s['renderedFrames']>0)
 record(name+' native starts',s['error'] is None,s.get('rendering'))
 configure('GPU 增强（输出 SDR）')
 s=fresh(lambda s:s.get('rendering',{}).get('operational') or s.get('error') is not None)
 record(name+' actual GPU SDR graph',s['error'] is None and s['rendering']['operational'],s)
 screenshot('gpu-'+name)
 configure('原生输出（保留 HDR）')
 s=fresh(lambda s:s.get('rendering',{}).get('requestedMode')=='NATIVE' and s['renderedFrames']>0)
 record(name+' native fallback works',s['error'] is None,s.get('rendering'))
print('Rendering graph checks passed',flush=True)
