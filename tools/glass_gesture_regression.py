"""Glass/gesture regression. Run with a dedicated SWEEFL_TEST_OUT; fixture server/reverse must exist.
Preserves user preferences and media volume. All playback uses benchmark mode (no history writes).
"""
import json, re, time, os, shlex
from pathlib import Path
from device_lab import adb, verify_device, OUT, ROOT, launch, snapshot
from ui_lab import ui, find, tap, tap_node, screenshot

results=[]
def record(name, ok, detail=None):
    results.append(dict(check=name, passed=bool(ok), detail=detail))
    (OUT/'glass-gesture-results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
    print(name, bool(ok), detail,flush=True)
    if not ok: raise AssertionError(name)
def bounds(node): return list(map(int,re.findall(r'\d+',node.get('bounds'))))
def input_cmd(command): return adb('shell','su -c '+shlex.quote('input '+command))
def settle(): time.sleep(2.2); return snapshot()
def cancel(): input_cmd('motionevent CANCEL 0 0')
def double(x,y=850):
    # Historical test coordinates were authored for 1280px-wide portrait; scale to target.
    match=re.search(r'(\d+)x(\d+)',adb('shell','wm','size'));width=min(map(int,match.groups()))
    x=round(x*width/1280);y=round(y*width/1280)
    input_cmd(f'tap {x} {y}; sleep 0.10; input tap {x} {y}')
def volume(): return int(re.search(r'volume is (\d+)',adb('shell','cmd','media_session','volume','--stream','3','--get'))[1])
def logs(pid): return adb('logcat','-d','--pid',pid,'-s','SweeflPreview:D','AndroidRuntime:E','*:S')
def show(label, tag):
    tag=re.sub(r'[<>:"/\\|?*]', '-', tag)
    for attempt in range(5):
        tree=ui(tag+'-'+str(attempt)); node=find(tree,label)
        if node is not None:
            x1,y1,x2,y2=bounds(node)
            if x2>x1 and y2-y1>=20: return tree
        areas=[n for n in tree.iter('node') if n.get('scrollable')=='true']
        if not areas: break
        x1,y1,x2,y2=bounds(areas[0]); x=(x1+x2)//2
        input_cmd(f'swipe {x} {int(y1+(y2-y1)*.78)} {x} {int(y1+(y2-y1)*.48)} 650')
    raise AssertionError('Control not reachable: '+label)
def choose(region, action):
    tap(show(region,'region-'+region),region)
    tap(show(action,'action-'+action),action)
    tree=ui('selected-'+re.sub(r'[<>:"/\\|?*]', '-', region+'-'+action))
    assert find(tree,'手势与进度预览') is not None,'Action picker did not close'
def preferences():
    import xml.etree.ElementTree as ET
    xml=adb('exec-out','su','-c','cat /data/user/0/com.sweefl.player/shared_prefs/player_preferences.xml')
    return {n.get('name'):n.get('value',n.text) for n in ET.fromstring(xml)}
def pause(tag):
    tree=ui(tag)
    if find(tree,'暂停') is not None: tap(tree,'暂停')
    return settle()
def ensure_portrait(tag):
    tree=ui(tag)
    x1,y1,x2,y2=bounds(next(tree.iter('node')))
    if x2-x1>y2-y1:
        tap(tree,'切换横竖屏'); ui(tag+'-portrait')

def slider_seek(fraction):
    tree=ui('slider-seek'); node=next(n for n in tree.iter('node') if n.get('class')=='android.widget.SeekBar')
    x1,y1,x2,y2=bounds(node); input_cmd(f'tap {int(x1+(x2-x1)*fraction)} {(y1+y2)//2}')
    cancel(); return settle()
def held_seek(name, start, end, finish='UP', ready=False):
    before=snapshot(); pid=adb('shell','pidof','com.sweefl.player').strip()
    input_cmd(f'motionevent DOWN {start[0]} {start[1]}')
    try:
        input_cmd(f'motionevent MOVE {end[0]} {end[1]}')
        time.sleep(2)
        tree=ui(name); screenshot(name)
        during=snapshot()
        labels=[n.get('content-desc','') for n in tree.iter('node') if n.get('content-desc','').startswith('进度预览 ')]
    finally:
        input_cmd(f'motionevent {finish} {end[0]} {end[1]}'); cancel()
    after=settle(); trace=logs(pid); (OUT/(name+'.log')).write_text(trace,encoding='utf-8')
    record(name+' stages without moving primary',abs(before['positionMs']-during['positionMs'])<500,[before['positionMs'],during['positionMs']])
    if ready: record(name+' displays verified target frame',any('画面已就绪' in x for x in labels),labels)
    if finish=='CANCEL': record(name+' cancellation does not seek',abs(after['positionMs']-before['positionMs'])<500)
    return before,after,labels,trace


from contextlib import contextmanager

@contextmanager
def preserve_device_state():
    verify_device()
    backup=Path(os.environ.get('SWEEFL_PREFERENCES_BACKUP',str(ROOT.parent/'.android-tools/tmp/sweefl-v030-user-settings.xml')))
    assert backup.is_file(),'Capture original settings before running this regression'
    owner=adb('shell','su','-c','stat -c %u:%g /data/user/0/com.sweefl.player/shared_prefs/player_preferences.xml').strip()
    assert re.fullmatch(r'\d+:\d+',owner)
    original_volume=volume()
    try:
        yield
    finally:
        cancel()
        adb('shell','am','force-stop','com.sweefl.player')
        adb('shell','su','-c',f'cmd media_session volume --stream 3 --set {original_volume}')
        remote='/data/local/tmp/sweefl-v030-restore-prefs.xml'
        adb('push',str(backup),remote)
        dest='/data/user/0/com.sweefl.player/shared_prefs/player_preferences.xml'
        adb('shell','su','-c',f'cp {remote} {dest}')
        assert adb('shell','su','-c',f'stat -c %u:%g {dest}').strip()==owner
        adb('shell','su -c '+shlex.quote(f'if [ -f {dest}.bak ]; then cp {dest} {dest}.bak; fi'))
        restored=adb('exec-out','su','-c',f'cat {dest}')
        assert restored==backup.read_text(encoding='utf-8')
        (OUT/'restoration.json').write_text(json.dumps({'preferencesRestored':True,'mediaVolume':original_volume}))

def main():
    with preserve_device_state():
        cancel()
        launch('synthetic/avc1080.mp4',extra=('--ez','keepControls','true'),key='glass-regression')
        pause('default-playing'); ensure_portrait('initial-orientation')
        p=slider_seek(.5)['positionMs']; double(180); q=settle()['positionMs']
        record('default left double tap seeks back ten seconds',abs((p-q)-10000)<800,[p,q])
        double(1100); r=settle()['positionMs']
        record('default right double tap seeks forward ten seconds',abs((r-q)-10000)<800,[q,r])
        double(640); record('default center double tap plays',settle()['isPlaying'])
        double(640); record('default center double tap pauses',not settle()['isPlaying'])
        tap(ui('custom-entry'),'手势与进度预览'); ui('gesture-settings-open'); screenshot('gesture-settings-portrait')
        choose('左侧区域','快进 30 秒'); choose('中间区域','不执行操作'); choose('右侧区域','后退 30 秒')
        config=preferences()
        record('all three mappings persist independently',all(config.get(k)==v for k,v in {
            'double_tap_left':'FORWARD_30','double_tap_center':'NONE','double_tap_right':'BACK_30'}.items()), {k:v for k,v in config.items() if k.startswith('double_tap_')})
        tree=show('显示进度预览','preview-toggle')
        switch=next(n for n in tree.iter('node') if n.get('checkable')=='true' and n.get('content-desc')=='显示进度预览')
        if switch.get('checked')=='true': tap_node(switch)
        tap(show('完成','settings-done'),'完成')
        p=slider_seek(.5)['positionMs']; double(640); q=settle()
        record('disabled center action changes neither pause nor position',not q['isPlaying'] and abs(q['positionMs']-p)<500)
        double(180); p=settle()['positionMs']; record('custom left action clamps forward at duration',p>=39000,p)
        double(1100); q=settle()['positionMs']; record('custom right action seeks back thirty seconds',abs((p-q)-30000)<800,[p,q])
        before,after,labels,trace=held_seek('horizontal-preview-off',(300,850),(900,850))
        record('horizontal swipe seeks forward on release',after['positionMs']>before['positionMs']+10000)
        record('preview off creates no preview player or window',not labels and 'created active=' not in trace)
        before,after,_,_=held_seek('horizontal-cancel',(900,850),(350,850),finish='CANCEL')
        before,after,_,_=held_seek('horizontal-backward',(900,850),(350,850))
        record('horizontal swipe seeks backward on release',after['positionMs']<before['positionMs']-10000)
        # Restart proves persisted mappings and disabled preview survive process recreation.
        launch('synthetic/avc1080.mp4',extra=('--ez','keepControls','true'),key='persisted-gestures')
        pause('persisted-paused'); ensure_portrait('persisted-orientation'); tap(ui('persisted-entry'),'手势与进度预览')
        record('gesture configuration survives process restart',preferences().get('double_tap_left')=='FORWARD_30' and preferences().get('seek_preview')=='false')
        tap(show('恢复默认手势','restore-default'),'恢复默认手势')
        input_cmd('swipe 540 800 540 1400 350')
        tree=show('显示进度预览','preview-on')
        switch=next(n for n in tree.iter('node') if n.get('checkable')=='true' and n.get('content-desc')=='显示进度预览')
        if switch.get('checked')!='true': tap_node(switch)
        tap(show('完成','settings-on-done'),'完成')
        record('restore defaults preserves independent preview choice',preferences().get('double_tap_center')=='PLAY_PAUSE' and preferences().get('seek_preview')=='true')
        slider_seek(.15)
        held_seek('horizontal-preview-on',(300,850),(900,850),ready=True)
        # Real slider preview for each transport, using the exact primary UA/MIME descriptor.
        cases=[('mp4','synthetic/avc1080.mp4',()),('hls','synthetic/hls/index.m3u8',()),('dash','synthetic/dash/index.mpd',())]
        for name,path,extra in cases:
            launch(path,extra=('--ez','keepControls','true','--ez','landscape','true',*extra),key='preview-'+name)
            pause(name+'-paused')
            tree=ui(name+'-slider'); node=next(n for n in tree.iter('node') if n.get('class')=='android.widget.SeekBar')
            x1,y1,x2,y2=bounds(node); y=(y1+y2)//2
            _,_,_,trace=held_seek('verified-'+name,(int(x1+(x2-x1)*.2),y),(int(x1+(x2-x1)*.6),y),ready=True)
            record(name+' preview resources release after drag','released active=0' in trace)
        # Library-owned dialogs share the same design system.
        adb('shell','am','force-stop','com.sweefl.player')
        adb('shell','am','start','-W','-f','0x10008000','-n','com.sweefl.player/.MainActivity')
        screenshot('final-library')
        print('Gesture regression finished.',flush=True)

if __name__ == "__main__":
    main()
