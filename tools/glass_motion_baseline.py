"""Capture UI rendering cadence and primary-video counters; parent restores device state."""
import csv,io,json,time
from device_lab import adb,OUT,launch,snapshot,verify_device
from ui_lab import ui,tap
from glass_gesture_regression import bounds

def measure(tag,seconds=8):
    time.sleep(2)
    adb('shell','dumpsys','gfxinfo','com.sweefl.player','reset')
    before=snapshot();start=time.monotonic();time.sleep(seconds);elapsed=time.monotonic()-start;after=snapshot()
    raw=adb('shell','dumpsys','gfxinfo','com.sweefl.player','framestats');(OUT/(tag+'-gfxinfo.txt')).write_text(raw,encoding='utf-8')
    log=adb('logcat','-d','-v','brief','SweeflGlass:D','*:S');(OUT/(tag+'-glass.log')).write_text(log,encoding='utf-8')
    times=[]
    for part in raw.split('---PROFILEDATA---'):
        rows=part.strip().splitlines()
        if rows and rows[0].startswith('Flags,'):
            for row in csv.DictReader(rows):
                try:
                    if 0<int(row['IntendedVsync'])<=int(row['FrameCompleted'])<2**62:times.append(int(row['IntendedVsync']))
                except (ValueError,KeyError,TypeError):pass
    times=sorted(set(times));cadence=(len(times)-1)/((times[-1]-times[0])/1e9) if len(times)>1 else None
    data={'seconds':elapsed,'uiRenderCadenceFps':cadence,'uiFrameSamples':len(times),'before':before,'after':after,
          'primaryRenderedFrames':after.get('renderedFrames',0)-before.get('renderedFrames',0),
          'primaryDroppedFrames':after.get('droppedFrames',0)-before.get('droppedFrames',0)}
    (OUT/(tag+'-measurement.json')).write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8');return data

if __name__=='__main__':
    verify_device();launch('synthetic/avc1080.mp4',extra=('--ez','keepControls','true'),key='glass-cadence-baseline')
    tree=ui('orientation');x1,y1,x2,y2=bounds(next(tree.iter('node')))
    if x2-x1<y2-y1:tap(tree,'切换横竖屏');ui('landscape')
    data=measure('baseline');print(json.dumps({k:v for k,v in data.items() if k not in ('before','after')},indent=2),flush=True)
