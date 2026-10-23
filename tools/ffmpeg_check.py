"""Real decoder selection evidence for explicit FFmpeg mode and HDR exclusion."""
import time
from device_lab import verify_device,launch,snapshot
from glass_gesture_regression import record
verify_device()
for name,path,expected in [('AVC','synthetic/avc1080.mp4',True),('HEVC','synthetic/hevc1080.mp4',True),('VP9','media3/vp9__bear-vp9.webm',True),('HDR10','media3/mp4__hdr10-720p.mp4',False),('HLG','media3/mp4__hlg-1080p.mp4',False)]:
 launch(path,mode='FFMPEG',extra=('--ez','keepControls','true'),key='ffmpeg-'+name)
 end=time.monotonic()+20
 s={};snapshot_error=None
 while time.monotonic()<end:
  try:s=snapshot()
  except Exception as error:
   snapshot_error=str(error);time.sleep(.25);continue
  if s.get('decodeMode')=='FFMPEG' and (s.get('error') or s.get('renderedFrames',0)>4):break
  time.sleep(.25)
 if not s:raise AssertionError('No diagnostics snapshot: '+str(snapshot_error))
 record(name+' playback succeeds',s.get('decodeMode')=='FFMPEG' and s.get('error') is None and s.get('renderedFrames',0)>4,s)
 decoder=(s.get('videoDecoder') or '').lower()
 record(name+' correct video path',('ffmpeg' in decoder)==expected,decoder)
print('FFmpeg selection checks passed',flush=True)
