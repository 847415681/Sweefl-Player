"""Missing owned fixture -> player error -> restore fixture -> UI retry -> actual frames."""
import json,os,shutil,time,uuid
from pathlib import Path
from device_lab import ROOT,OUT,verify_device,launch,snapshot
from ui_lab import ui,tap,screenshot
from glass_gesture_regression import record
verify_device()
fixtures=Path(os.environ.get('SWEEFL_FIXTURES',str(ROOT.parent/'.android-tools/media-fixtures'))).resolve()
source=fixtures/'synthetic/seek-epoch-rgb.mp4'
name='Sweefl-retry-'+uuid.uuid4().hex+'.mp4';target=fixtures/'synthetic'/name
assert source.is_file() and not target.exists()
def wait(pred):
 end=time.monotonic()+20
 while time.monotonic()<end:
  s=snapshot()
  if pred(s):return s
  time.sleep(.2)
 raise AssertionError(s)
try:
 launch('synthetic/'+name,extra=('--ez','keepControls','true'),key='retry')
 failed=wait(lambda s:s.get('error') is not None);screenshot('error')
 shutil.copyfile(source,target)
 tap(ui('retry-error'),'重试')
 recovered=wait(lambda s:s.get('error') is None and s.get('renderedFrames',0)>5 and s['isPlaying'])
 record('retry rebuild clears error and presents frames',True,recovered)
 (OUT/'retry-result.json').write_text(json.dumps({'before':failed,'after':recovered},ensure_ascii=False,indent=2))
 screenshot('recovered')
finally:
 assert target.parent==fixtures/'synthetic' and target.name==name
 if target.exists():target.unlink()
