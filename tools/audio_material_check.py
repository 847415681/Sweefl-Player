"""Audio-only playback must retain reachable controls without waiting for video pixels."""
import time
from device_lab import verify_device,launch,snapshot
from ui_lab import ui,tap,find,screenshot
verify_device()
launch('synthetic/audio.mp3',extra=('--ez','keepControls','true'),key='audio-material')
end=time.monotonic()+20
while time.monotonic()<end:
 t=ui('audio-playing')
 if find(t,'暂停') is not None:break
 time.sleep(.2)
else:raise AssertionError('Audio controls never became available')
tap(t,'暂停');t=ui('audio-paused');assert find(t,'播放') is not None
screenshot('audio-paused')
end=time.monotonic()+5
while time.monotonic()<end:
 if not snapshot()['isPlaying']:break
 time.sleep(.2)
else:raise AssertionError('Audio pause failed')
print('Audio-only optical controls and pause passed',flush=True)
