"""Capture actual STATE_BUFFERING following a far seek; use delayed fixture server."""
import time,json,re,shlex,subprocess,uuid
from device_lab import adb,launch,snapshot,OUT,verify_device,ADB,SERIAL
from ui_lab import ui,tap,find,screenshot
def bounds(node): return list(map(int,re.findall(r'\d+',node.get('bounds'))))
def input_cmd(command): return adb('shell','su -c '+shlex.quote('input '+command))
verify_device()
launch('synthetic/avc1080.mp4',extra=('--ez','keepControls','true','--ez','benchmark','false'),key='loading-material')
deadline=time.monotonic()+35
while time.monotonic()<deadline:
 s=snapshot()
 if s.get('renderedFrames',0)>10:break
 time.sleep(.2)
else:raise AssertionError('No initial video frame')
t=ui('before-seek');rb=bounds(next(t.iter('node')))
if rb[2]-rb[0]<rb[3]-rb[1]:tap(t,'切换横竖屏');t=ui('landscape')
screenshot('before-seek')
sl=next(n for n in t.iter('node') if n.get('class')=='android.widget.SeekBar');a,b,c,d=bounds(sl)
remote='/sdcard/Download/Sweefl-loading-'+uuid.uuid4().hex+'.mp4'
with (OUT/'record.log').open('w') as log:
 proc=subprocess.Popen([ADB,'-s',SERIAL,'shell','screenrecord','--time-limit','15','--bit-rate','16000000',remote],stdout=log,stderr=log)
time.sleep(.5)
input_cmd(f'tap {int(a+(c-a)*.88)} {(b+d)//2}')
observations=[]
for i in range(18):
 s=snapshot();observations.append(s)
 if s.get('playbackState') == 2:screenshot('loading-'+str(i))
 time.sleep(.2)
proc.wait(timeout=20)
assert proc.returncode==0
adb('pull',remote,str(OUT/'loading.mp4'));adb('shell','rm',remote)
(OUT/'loading-states.json').write_text(json.dumps(observations,ensure_ascii=False,indent=2))
assert any(s.get('playbackState') == 2 for s in observations),'No actual loading was observed; do not claim visual pass'
print('Actual loading observed; inspect captured material continuity',flush=True)
