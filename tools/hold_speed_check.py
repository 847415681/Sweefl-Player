"""Exercise temporary hold speed on the actual engine; parent owns state restoration."""
import json,time
from device_lab import adb,OUT,launch,snapshot,verify_device
from ui_lab import ui,find,tap,screenshot
from glass_gesture_regression import record,show,bounds,input_cmd,cancel,preferences,volume
verify_device()
try:minimum_hold=max(.8,int(adb("shell","settings","get","secure","long_press_timeout").strip())/1000+.25)
except ValueError:minimum_hold=.8

def wait_speed(value,after=None,playing=None):
    if after is None:after=snapshot()['elapsedRealtimeMs']
    until=time.monotonic()+9
    while time.monotonic()<until:
        s=snapshot()
        if s['elapsedRealtimeMs']>after and abs(s.get('playbackSpeed',-1)-value)<.02 and (playing is None or s['isPlaying']==playing):return s
        time.sleep(.25)
    raise AssertionError(('engine speed did not settle',value,s))
def choose_speed(current,value):
    tap(show(f'{current}×','speed-open'),f'{current}×');tap(show(f'{value}×','speed-choice'),f'{value}×');ui('speed-selected');return wait_speed(value)
def press(expected):
    before=snapshot()['elapsedRealtimeMs'];input_cmd(f'motionevent DOWN {px} {py}');return wait_speed(expected,before)
def release(expected,kind='UP'):
    before=snapshot()['elapsedRealtimeMs'];input_cmd(f'motionevent {kind} {px} {py}');return wait_speed(expected,before)

try:
    launch('synthetic/avc1080.mp4',extra=('--ez','keepControls','true'),key='hold-speed')
    tree=ui('initial');a,b,c,d=bounds(next(tree.iter('node')))
    if c-a>d-b:tap(tree,'切换横竖屏');tree=ui('portrait')
    a,b,c,d=bounds(next(tree.iter('node')));px,py=int(c*.15),int(d*.4)
    wait_speed(1.0);choose_speed(1.0,1.5)
    held=press(2.0);tree=ui('hold-default');screenshot('hold-default-2x')
    record('default hold requests actual 2x',held['playbackSpeed']==2.0)
    record('hold feedback replaces central controls',find(tree,'长按 2.0× · 松手恢复') is not None and find(tree,'后退 10 秒') is None)
    initial_volume=volume();input_cmd(f'motionevent MOVE {px+250} {py-250}');wait_speed(2.0);tree=ui('hold-moved')
    record('moving after hold does not start volume or seeking',volume()==initial_volume and find(tree,'长按 2.0× · 松手恢复') is not None and not any('亮度 ' in n.get('text','') or '音量 ' in n.get('text','') for n in tree.iter('node')))
    release(1.5);record('release restores non-default original speed',True)
    press(2.0);release(1.5,'CANCEL');record('pointer cancellation restores original speed',True)
    tap(show('手势与进度预览','hold-settings'),'手势与进度预览')
    tap(show('长按临时倍速','hold-choice-entry'),'长按临时倍速');tap(show('3.0×','hold-choice'),'3.0×');ui('hold-choice-applied')
    record('three-times hold preference persists',float(preferences().get('long_press_speed','0'))==3.0)
    screenshot('hold-speed-setting');tap(show('完成','hold-done'),'完成')
    press(3.0);screenshot('hold-custom-3x');release(1.5);record('configured hold uses 3x and restores 1.5x',True)
    choose_speed(1.5,3.0);record('regular speed menu supports actual 3x',True);choose_speed(3.0,1.5)
    # A drag crosses slop before the long-press timeout and retains exclusive ownership.
    input_cmd(f'motionevent DOWN {px} {py}');input_cmd(f'motionevent MOVE {px+200} {py}')
    wait_speed(1.5);tree=ui('drag-held')
    record('drag first prevents hold activation',find(tree,'长按 3.0× · 松手恢复') is None)
    cancel();ui('drag-cancelled')
    tap(show('暂停','pause-entry'),'暂停');wait_speed(1.5,playing=False)
    input_cmd(f'motionevent DOWN {px} {py}');time.sleep(minimum_hold);wait_speed(1.5,playing=False);release(1.5)
    record('holding paused video does not start playback',snapshot()['isPlaying']==False)
    tap(show('播放','resume'),'播放');wait_speed(1.5,playing=True)
    tap(show('锁定触控，轻触屏幕后可解锁','lock'),'锁定触控，轻触屏幕后可解锁')
    ui("lock-stable")
    input_cmd(f'motionevent DOWN {px} {py}');time.sleep(minimum_hold);wait_speed(1.5);release(1.5)
    record('locked playback ignores hold speed',True)
    tree=ui('locked-after-release')
    for attempt in range(3):
        if find(tree,'解锁控制') is not None:break
        time.sleep(.4);input_cmd(f'tap {px} {py}');tree=ui('unlock-visible-'+str(attempt))
    tap(tree,'解锁控制');ui('unlocked')
    press(3.0);input_cmd('keyevent 3');wait_speed(1.5)
    record('home and automatic PiP restore original speed',True)
    # Restart validates settings persistence independently of the old in-memory object.
    cancel();launch('synthetic/avc1080.mp4',extra=('--ez','keepControls','true'),key='hold-restart')
    tree=ui('restarted');a,b,c,d=bounds(next(tree.iter('node')));px,py=int(c*.15),int(d*.4)
    wait_speed(1.0);press(3.0);release(1.0);record('hold setting survives process restart',True)
finally:
    cancel()
print('Hold speed checks finished.',flush=True)
