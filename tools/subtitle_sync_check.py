"""Exact paused subtitle timing, first seek, persistence and EOF contract checks."""
import re,time,uuid,shlex
from device_lab import adb,launch,snapshot,verify_device,OUT
from ui_lab import ui,find,tap,screenshot
from glass_gesture_regression import bounds,record,input_cmd
verify_device()
seq=0

def tree():
 global seq
 seq+=1
 return ui('sync-'+str(seq))
def fresh(predicate):
 prior=snapshot().get('elapsedRealtimeMs',0);until=time.monotonic()+8
 while time.monotonic()<until:
  s=snapshot()
  if s.get('elapsedRealtimeMs',0)>prior and predicate(s):return s
  time.sleep(.2)
 raise AssertionError(('fresh subtitle state',s))
def controls():
 t=tree()
 if find(t,'字幕与音轨') is None:
  a,b,c,d=bounds(next(t.iter('node')));input_cmd(f'tap {int(c*.15)} {int(d*.38)}');t=tree()
 assert find(t,'字幕与音轨') is not None
 return t
def pause():
 t=controls()
 if find(t,'暂停') is not None:tap(t,'暂停')
 return fresh(lambda s:s['playbackState']==3 and not s['isPlaying'])
def dismiss():
 for unused in range(2):
  t=tree()
  if find(t,'完成') is None:break
  tap(t,'完成')
slider_bounds=None
def seek(ms):
 global slider_bounds
 t=controls();bar=next(n for n in t.iter('node') if n.get('class')=='android.widget.SeekBar')
 a,b,c,d=bounds(bar)
 if slider_bounds is None:slider_bounds=(a,b,c,d)
 else:record('time digits keep slider geometry stable',slider_bounds==(a,b,c,d))
 densities=re.findall(r'(?:Physical|Override) density: (\d+)',adb('shell','wm','density'))
 half=10*int(densities[-1])/160;travel=c-a-2*half
 x=a+half+travel*ms/40000
 for unused in range(3):
  input_cmd(f'motionevent DOWN {(a+c)//2} {(b+d)//2}')
  input_cmd(f'motionevent MOVE {int(x)} {(b+d)//2}')
  input_cmd(f'motionevent UP {int(x)} {(b+d)//2}')
  s=fresh(lambda s:s['playbackState']==3 and not s['isPlaying'])
  if abs(s['positionMs']-ms)<200:return s
  x=max(a+half,min(c-half,x+travel*(ms-s['positionMs'])/40000))
 raise AssertionError(('seek calibration',ms,s))
def attach(name):
 tap(controls(),'字幕与音轨');tap(tree(),'加载外置字幕（SRT / VTT / ASS）');t=tree()
 if find(t,name) is None:
  if find(t,'显示根目录') is not None:tap(t,'显示根目录');t=tree()
  for label in ['浏览','Download','Downloads','下载','下载内容']:
   if find(t,label) is not None:tap(t,label);t=tree()
 for unused in range(8):
  if find(t,name) is not None:break
  a,b,c,d=bounds(next(t.iter('node')));input_cmd(f'swipe {int(c*.7)} {int(d*.78)} {int(c*.7)} {int(d*.3)} 350');t=tree()
 tap(t,name);t=tree()
 if find(t,'确定') is not None:tap(t,'确定')
 return fresh(lambda s:s['playbackState']==3 and not s['isPlaying'])
def open_sync():
 tap(controls(),'字幕与音轨');tap(tree(),'字幕同步（仅外挂字幕）');return tree()
def read_offset(t):
 return next(int(m.group(1)) for n in t.iter('node') if (m:=re.fullmatch(r'([+-]?\d+) ms',n.get('text',''))))
def set_offset(value):
 before=snapshot();t=open_sync();current=read_offset(t)
 delta=value-current;assert delta%1000==0
 if delta:
  label='延后 1 秒' if delta>0 else '提前 1 秒'
  a,b,c,d=bounds(find(t,label));command=f'input tap {(a+c)//2} {(b+d)//2}'
  adb('shell','su','-c','; '.join([command]*(abs(delta)//1000)),timeout=60)
 expected=max(-60000,min(60000,value));record('offset UI value',read_offset(tree())==expected,expected)
 dismiss()
 s=fresh(lambda s:not s['isPlaying'])
 record('offset does not seek or alter speed',abs(s['positionMs']-before['positionMs'])<=1 and s['playbackSpeed']==before['playbackSpeed'])
 return s
def expect_cues(s,expected,label):
 record(label,sorted(s.get('cueTexts',[]))==sorted(expected),{'cues':s.get('cueTexts'),'positionMs':s['positionMs'],'textTracks':s.get('textTracks')})

tag=uuid.uuid4().hex[:6]
srt=f'Sweefl-sync-v032-{tag}.srt';vtt=f'Sweefl-sync-v032-{tag}.vtt'
fixtures={srt:'1\n00:00:01,000 --> 00:00:03,000\nSWEEFL-SYNC-A\n\n2\n00:00:05,000 --> 00:00:07,000\nSWEEFL-SYNC-B\n\n3\n00:00:09,000 --> 00:00:11,000\nSWEEFL-SYNC-C\n',vtt:'WEBVTT\n\nSTYLE\n::cue(.lime) { color: lime; font-style: italic; }\n\n00:00:01.000 --> 00:00:10.000\n<c.lime>VTT-BASE</c>\n\n00:00:05.000 --> 00:00:07.000\nVTT-OVERLAP\n'}
for name,data in fixtures.items():
 p=OUT/name;p.write_text(data,encoding='utf-8');adb('push',str(p),'/sdcard/Download/'+name)
extra=('--ez','keepControls','true','--ez','benchmark','false')
launch('synthetic/avc1080.mp4',extra=extra,key='subtitle-sync-v032');pause();seek(8200);attach(srt)
expect_cues(set_offset(3000),['SWEEFL-SYNC-B'],'first seek can show an earlier unvisited cue')
seek(2000);expect_cues(set_offset(2000),[],'positive delay clears cue while paused')
expect_cues(set_offset(-4000),['SWEEFL-SYNC-B'],'negative offset reveals later cue while paused')
seek(6000);expect_cues(set_offset(4000),['SWEEFL-SYNC-A'],'positive offset looks back to earlier cue')
screenshot('paused-lookback-A')
seek(0);expect_cues(set_offset(1000),[],'positive offset never shows early cue at start')
set_offset(65000);set_offset(-65000);set_offset(3000)
launch('synthetic/avc1080.mp4',extra=extra,key='subtitle-sync-v032');pause();seek(8200);attach(srt)
t=open_sync();record('per-video offset survives process restart',read_offset(t)==3000);dismiss()
expect_cues(fresh(lambda s:not s['isPlaying']),['SWEEFL-SYNC-B'],'restarted source restores delayed earlier cue')
attach(vtt);seek(6000)
expect_cues(set_offset(0),['VTT-BASE','VTT-OVERLAP'],'file switch rejects old cues and preserves overlap')
screenshot('vtt-overlap-style')
expect_cues(set_offset(4000),['VTT-BASE'],'offset preserves VTT styled cue')
attach(srt);set_offset(35000);seek(39000);tap(controls(),'播放')
ended=fresh(lambda s:s['playbackState']==4)
record('delayed captions do not extend video EOF',ended['positionMs']>=39900 and ended['cueCount']==0,ended)
print('Subtitle synchronization checks passed.',flush=True)
