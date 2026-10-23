"""Verify minimal immersive feedback against actual playback state."""
import time
from device_lab import adb,launch,snapshot,verify_device
from ui_lab import ui,find,screenshot
from glass_gesture_regression import input_cmd,cancel,record,bounds,double
verify_device()
BARS=('返回媒体库','播放信息与解码','手势与进度预览','字幕与音轨','切换横竖屏','暂停','播放')
def minimal(tree,slider=False):
    return all(find(tree,x) is None for x in BARS) and (any(n.get('class')=='android.widget.SeekBar' for n in tree.iter('node'))==slider)
def state(speed=None,playing=None):
    prior=snapshot()['elapsedRealtimeMs'];until=time.monotonic()+7
    while time.monotonic()<until:
        s=snapshot()
        if s['elapsedRealtimeMs']>prior and (speed is None or abs(s.get('playbackSpeed',-1)-speed)<.02) and (playing is None or s['isPlaying']==playing):return s
        time.sleep(.2)
    raise AssertionError(('playback state',speed,playing,s))
try:
    launch('synthetic/avc1080.mp4',extra=('--ez','keepControls','true'),key='immersive')
    tree=ui('initial');a,b,w,h=bounds(next(tree.iter('node')));x,y=int(w*.18),int(h*.38)
    input_cmd(f'tap {x} {y}');record('single tap enters immersive playback',minimal(ui('hidden')))
    double(int(w*.5),y);screenshot('immersive-paused-feedback');state(playing=False)
    record('double pause preserves immersive controls',minimal(ui('paused-no-bars')))
    double(int(w*.5),y);state(playing=True)
    record('double resume preserves immersive controls',minimal(ui('resumed-no-bars')))
    input_cmd(f'motionevent DOWN {x} {y}');state(speed=2.0)
    tree=ui('hold');node=find(tree,'长按 2.0× · 松手恢复');screenshot('immersive-hold-feedback')
    record('hold shows only a small top hint',node is not None and bounds(node)[1]>=0 and bounds(node)[3]<h*.35 and bounds(node)[3]-bounds(node)[1]<h*.18 and minimal(tree))
    input_cmd(f'motionevent UP {x} {y}');state(speed=1.0)
    record('hold release restores speed without bars',minimal(ui('hold-released')))
    double(int(w*.5),y);before=state(playing=False)
    input_cmd(f'motionevent DOWN {x} {y}');input_cmd(f'motionevent MOVE {x+int(w*.055)} {y}')
    ready=False
    for i in range(4):
        tree=ui('scrub-'+str(i));nodes=[n for n in tree.iter('node') if '进度预览 ' in n.get('content-desc','')]
        if nodes and '画面已就绪' in nodes[0].get('content-desc',''):ready=True;break
    screenshot('immersive-scrub-preview')
    record('horizontal drag shows slider and verified preview without bars',ready and minimal(tree,True))
    a,b,c,d=bounds(nodes[0]);record('preview frame remains compact with time inside',abs((c-a)/(d-b)-16/9)<.08)
    record('drag preview does not seek primary player',abs(snapshot()['positionMs']-before['positionMs'])<100)
    cancel();state(playing=False)
    record('cancel removes temporary seek overlay',minimal(ui('scrub-cancelled')))
    record('cancel keeps paused playback position',abs(snapshot()['positionMs']-before['positionMs'])<100)
finally:cancel()
print('Immersive checks finished.',flush=True)
