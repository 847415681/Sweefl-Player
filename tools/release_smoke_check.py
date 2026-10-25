"""Release APK UI playback smoke without relying on debug-only diagnostics."""
import time
from device_lab import verify_device,launch,adb,OUT
from ui_lab import ui,tap,find,screenshot
verify_device()
for name,path,mode in [('mp4','synthetic/avc1080.mp4','AUTO'),('hls','synthetic/hls/index.m3u8','AUTO'),('dash','synthetic/dash/index.mpd','AUTO'),('ffmpeg','synthetic/seek-epoch-rgb.mp4','FFMPEG')]:
 launch(path,mode=mode,extra=('--ez','keepControls','true'),key='release-'+name)
 time.sleep(3);t=ui(name+'-playing');assert find(t,'暂停') is not None
 # Release ignores debug keepControls; revealing controls can consume the first tap.
 for attempt in range(4):
  tap(t,'暂停');t=ui(name+'-paused-'+str(attempt))
  if find(t,'播放') is not None:break
  if find(t,'暂停') is None:
   from glass_gesture_regression import input_cmd
   input_cmd('tap 540 500');t=ui(name+'-reveal')
 assert find(t,'播放') is not None
 screenshot(name+'-paused')
 assert adb('shell','pidof','com.sweefl.player').strip()
 print(name+' release playback/pause alive',flush=True)
