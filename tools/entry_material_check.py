"""Record video entry, control reveal/hide, resize and exit without user data changes."""
import subprocess,time,uuid
from device_lab import verify_device,launch,adb,ADB,SERIAL,OUT
from ui_lab import ui,tap,find,screenshot
from glass_gesture_regression import bounds,input_cmd
verify_device()
remote='/sdcard/Download/Sweefl-entry-'+uuid.uuid4().hex+'.mp4'
with (OUT/'record.log').open('w') as log:
 proc=subprocess.Popen([ADB,'-s',SERIAL,'shell','screenrecord','--time-limit','30','--bit-rate','16000000',remote],stdout=log,stderr=log)
try:
 time.sleep(.5)
 launch('synthetic/seek-epoch-rgb.mp4',key='entry-material')
 t=ui('entered')
 if find(t,'暂停') is not None:tap(t,'暂停');t=ui('paused')
 n=find(t,'填满画面，裁切边缘')
 if n is None:n=find(t,'恢复完整画面')
 assert n is not None
 a,b,c,d=bounds(n);x=(a+c)//2;y=(b+d)//2
 for _ in range(6):input_cmd(f'tap {x} {y}');time.sleep(.45)
 screenshot('after-resize')
 rb=bounds(next(t.iter('node')));w=rb[2]-rb[0];h=rb[3]-rb[1]
 for _ in range(4):input_cmd(f'tap {int(w*.50)} {int(h*.30)}');time.sleep(.6)
 t=ui('before-exit')
 if find(t,'返回媒体库') is None:input_cmd(f'tap {int(w*.50)} {int(h*.30)}');t=ui('controls-revealed')
 tap(t,'返回媒体库');ui('exited');screenshot('exited')
 proc.wait(timeout=35)
finally:
 if proc.poll() is None:proc.terminate();proc.wait(timeout=5)
assert proc.returncode==0
adb('pull',remote,str(OUT/'entry-resize-exit.mp4'));adb('shell','rm',remote)
print('Entry, six resize toggles, control reveal/hide and exit recorded',flush=True)
