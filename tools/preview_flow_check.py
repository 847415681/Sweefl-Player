"""Measure cold/warm/continuous/EOF preview behavior from timestamp-verified trace events."""
import json,os,re,shlex,time,uuid
from device_lab import adb,OUT,launch,verify_device,snapshot
from ui_lab import ui,tap,screenshot
from glass_gesture_regression import bounds,input_cmd,cancel,record
verify_device();token='FLOW_'+uuid.uuid4().hex[:10];phases=[]
def mark(label):adb('shell','log','-p','d','-t','SweeflPreview',token+'_'+label)
def hold(label,dx,seconds=2.6):
    mark(label+'_BEGIN');input_cmd(f'motionevent DOWN {x} {y}');input_cmd(f'motionevent MOVE {x+dx} {y}')
    time.sleep(seconds);mark(label+'_END');cancel();phases.append(label)
try:
    launch(os.environ.get('SWEEFL_PREVIEW_FIXTURE','synthetic/avc1080.mp4'),extra=('--ez','keepControls','true'),key=token)
    tree=ui('initial');tap(tree,'暂停');tree=ui('paused');a,b,w,h=bounds(next(tree.iter('node')));x,y=int(w*.15),int(h*.4)
    hold('cold',int(w*.04));time.sleep(.15);hold('warm',int(w*.045))
    time.sleep(1.5)
    mark('continuous_BEGIN')
    input_cmd(f'motionevent DOWN {x} {y}')
    commands=[f'input motionevent MOVE {x+int(w*(.025+i*.002))} {y}; sleep .03' for i in range(28)]
    adb('shell','su','-c','; '.join(commands),timeout=30)
    mark('continuous_END');phases.append('continuous');screenshot('continuous-preview');cancel();time.sleep(1.5)
    tree=ui('eof-position');slider=next(n for n in tree.iter('node') if n.get('class')=='android.widget.SeekBar');a,b,c,d=bounds(slider)
    input_cmd(f'tap {int(a+(c-a)*.7)} {(b+d)//2}')
    deadline=time.monotonic()+6
    while time.monotonic()<deadline:
        state=snapshot()
        if state['positionMs']>20000:break
        time.sleep(.25)
    assert state['positionMs']>20000,state
    hold('eof',int(w*.6),3.2)
    time.sleep(1.6)
    raw=adb('logcat','-d','-v','epoch','-s','SweeflPreview:D','*:S');(OUT/'preview-flow-logcat.txt').write_text(raw,encoding='utf-8')
    rows=[]
    for line in raw.splitlines():
        m=re.match(r'\s*(\d+\.\d+)\s+.*?SweeflPreview\s*:\s*(.*)',line)
        if m:rows.append((float(m[1]),m[2]))
    summary=[]
    for label in phases:
        start=next(t for t,s in rows if token+'_'+label+'_BEGIN' in s);end=next(t for t,s in rows if token+'_'+label+'_END' in s)
        events=[(t,s) for t,s in rows if start<=t<=end];ready=[(t,s) for t,s in events if s.startswith('ready ')]
        summary.append(dict(phase=label,windowSeconds=end-start,verifiedFrames=len(ready),firstVerifiedMs=(ready[0][0]-start)*1000 if ready else None,created=sum(s.startswith('created ') for _,s in events),events=events))
    (OUT/'preview-flow-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(summary,ensure_ascii=False),flush=True)
    if os.environ.get('SWEEFL_PREVIEW_BASELINE')!='1':
        for item in summary:record(item['phase']+' produces verified frames',item['verifiedFrames']>0,item)
        record('EOF preview verifies the last frame',any('target=40000 ' in s for _,s in summary[3]['events'] if s.startswith('ready ')))
        record('short repeated drag reuses prepared decoder',summary[1]['created']==0,summary[1])
        record('continuous drag produces frames before finger stops',summary[2]['verifiedFrames']>=2,summary[2])
finally:cancel()
