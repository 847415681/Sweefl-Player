"""Additional real-device coverage for preview ownership, retargeting and fallbacks."""
import time,json,re,uuid,shlex
from pathlib import Path
from device_lab import adb,verify_device,OUT,launch,snapshot
from ui_lab import ui,find,tap,screenshot
from glass_gesture_regression import bounds,input_cmd,cancel,settle,pause,held_seek,record,logs,choose,show,double,ensure_portrait,preserve_device_state

def slider(tree):
    node=next(n for n in tree.iter('node') if n.get('class')=='android.widget.SeekBar')
    x1,y1,x2,y2=bounds(node); return x1,x2,(y1+y2)//2

def begin_drag(x1,x2,y):
    input_cmd(f'motionevent DOWN {int(x1+(x2-x1)*.2)} {y}')
    input_cmd(f'motionevent MOVE {int(x1+(x2-x1)*.6)} {y}')

def preview_labels(tree):
    return [n.get('content-desc','') for n in tree.iter('node') if n.get('content-desc','').startswith('进度预览 ')]

with preserve_device_state():
    cancel()
    # Local URI grants and the original MIME descriptor work without an HTTP source.
    adb('shell','am','force-stop','com.sweefl.player')
    uri='content://media/external/video/media/529'
    assert 'Sweefl-UI-Test-01.mp4' in adb('shell','su','-c',f'content query --uri {uri} --projection _display_name')
    adb('shell','am','start','-W','-f','0x10008000','-n','com.sweefl.player/.PlayerActivity','-d',uri,
        '--ez','benchmark','true','--ez','diagnostics','true','--ez','keepControls','true')
    pause('local-paused'); x1,x2,y=slider(ui('local-slider'))
    _,_,labels,trace=held_seek('sparse-local-fallback',(int(x1+(x2-x1)*.2),y),(int(x1+(x2-x1)*.6),y))
    record('sparse-frame local source safely falls back',not any('画面已就绪' in x for x in labels) and 'released active=0' in trace)
    
    name='Sweefl-QA-0.3.0-preview-'+uuid.uuid4().hex[:12]+'.mp4'
    remote='/sdcard/Movies/'+name
    expected='/storage/emulated/0/Movies/'+name
    fixture=Path(__file__).resolve().parents[2]/'.android-tools/media-fixtures/synthetic/avc1080.mp4'
    local_uri=None
    adb('push',str(fixture),remote)
    try:
        adb('shell','su -c '+shlex.quote(f'am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://{remote}'))
        for _ in range(8):
            row=adb('shell','su -c '+shlex.quote(f'content query --uri content://media/external/video/media --projection _id:_display_name --where "_display_name=\'{name}\'"'))
            match=re.search(r'_id=(\d+)',row)
            if match: local_uri='content://media/external/video/media/'+match[1]; break
            time.sleep(.5)
        assert local_uri,'Scanner did not register isolated local fixture'
        adb('shell','am','force-stop','com.sweefl.player')
        adb('shell','su -c '+shlex.quote(f'am start -W -f 0x10008001 -n com.sweefl.player/.PlayerActivity -d {local_uri} --ez benchmark true --ez diagnostics true --ez keepControls true'))
        pause('local-cfr-paused'); x1,x2,y=slider(ui('local-cfr-slider'))
        held_seek('verified-local',(int(x1+(x2-x1)*.2),y),(int(x1+(x2-x1)*.6),y),ready=True)
    finally:
        adb('shell','am','force-stop','com.sweefl.player')
        resolved=adb('shell','su','-c',f'readlink -f {remote}').strip()
        assert resolved==expected
        if local_uri:
            row=adb('shell','su','-c',f'content query --uri {local_uri} --projection _display_name')
            assert name in row
            adb('shell','su','-c',f'content delete --uri {local_uri}')
        adb('shell','su -c '+shlex.quote(f'if [ -f {expected} ]; then rm {expected}; fi'))
        adb('shell','su','-c',f'am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://{expected}')
        if local_uri:
            for _ in range(8):
                row=adb('shell','su','-c',f'content query --uri {local_uri} --projection _display_name')
                if name not in row: break
                time.sleep(.5)
            assert name not in row,'Test media index still exists'
        (OUT/'local-fixture-cleanup.json').write_text(json.dumps({'name':name,'uri':local_uri,'removed':True,'indexRemoved':True}))
    
    launch('synthetic/avc1080.mp4',extra=('--ez','keepControls','true'),key='rapid-retarget')
    pause('rapid-paused'); x1,x2,y=slider(ui('rapid-slider')); pid=adb('shell','pidof','com.sweefl.player').strip()
    a=int(x1+(x2-x1)*.6); b=int(x1+(x2-x1)*.3)
    input_cmd(f'motionevent DOWN {int(x1+(x2-x1)*.2)} {y}')
    try:
        input_cmd(f'motionevent MOVE {a} {y}'); time.sleep(.2)
        input_cmd(f'motionevent MOVE {b} {y}'); time.sleep(.05)
        input_cmd(f'motionevent MOVE {a} {y}'); time.sleep(2)
        labels=preview_labels(ui('rapid-a-b-a')); screenshot('rapid-a-b-a')
        record('A-B-A retarget displays verified final target',any('画面已就绪' in x for x in labels),labels)
    finally: input_cmd(f'motionevent CANCEL {a} {y}'); cancel()
    trace=logs(pid); (OUT/'rapid-retarget.log').write_text(trace,encoding='utf-8')
    record('A-B-A does not enter timeout fallback','unavailable' not in trace,trace)
    
    # Stop after READY while pointer is held; the preview must release without waiting for disposal/timeout.
    launch('synthetic/avc1080.mp4',extra=('--ez','keepControls','true'),key='stop-preview')
    pause('stop-paused'); x1,x2,y=slider(ui('stop-slider')); pid=adb('shell','pidof','com.sweefl.player').strip()
    begin_drag(x1,x2,y); time.sleep(2)
    labels=preview_labels(ui('before-background'))
    record('background test starts with a verified frame',any('画面已就绪' in x for x in labels))
    input_cmd('keyevent 3'); time.sleep(1)
    trace=logs(pid); (OUT/'background-preview.log').write_text(trace,encoding='utf-8')
    record('background releases ready preview immediately','released active=0' in trace and 'unavailable' not in trace)
    cancel()
    launch('synthetic/avc1080.mp4',extra=('--ez','keepControls','true'),key='resume-preview')
    tree=ui('resume-no-preview'); record('fresh playback has no stale preview window',not preview_labels(tree))
    pause('actions-paused'); ensure_portrait('action-orientation'); tap(ui('actions-entry'),'手势与进度预览')
    choose('中间区域','显示 / 隐藏控件'); choose('右侧区域','锁定触控'); tap(show('完成','actions-done'),'完成')
    double(640); time.sleep(.5); record('custom controls action hides controls',find(ui('controls-hidden'),'播放') is None)
    double(640); time.sleep(.5); record('custom controls action restores controls',find(ui('controls-restored'),'播放') is not None)
    double(1100); time.sleep(.5); input_cmd('tap 640 850'); time.sleep(.5)
    tree=ui('custom-lock'); record('custom lock action locks playback controls',find(tree,'解锁控制') is not None and find(tree,'播放') is None)
    tap(tree,'解锁控制')
    tap(ui('defaults-entry'),'手势与进度预览'); tap(show('恢复默认手势','defaults'),'恢复默认手势'); tap(show('完成','defaults-done'),'完成')
    
    launch('synthetic/audio.mp3',extra=('--ez','keepControls','true'),key='preview-no-video')
    pause('audio-paused'); x1,x2,y=slider(ui('audio-slider'))
    _,_,labels,trace=held_seek('audio-preview-fallback',(int(x1+(x2-x1)*.2),y),(int(x1+(x2-x1)*.6),y))
    record('audio-only source falls back without false preview',not any('画面已就绪' in x for x in labels) and 'released active=0' in trace)
    record('preview failure leaves primary playable',snapshot().get('error') is None)
    
    launch('media3/mp4__hdr10-720p.mp4',extra=('--ez','keepControls','true','--ez','landscape','true'),key='hdr-glass')
    first=settle(); time.sleep(3); second=snapshot()
    record('native HDR playback keeps rendering with glass',not second.get('error') and second.get('renderedFrames',0)>first.get('renderedFrames',0),second.get('videoFormat'))
    screenshot('hdr-native-glass')
    # Capture every remaining app-owned subdialog without changing decoder configuration.
    tap(ui('details-entry'),'播放信息与解码'); ui('glass-details'); screenshot('glass-details'); input_cmd('keyevent 4')
    tap(ui('tracks-entry'),'字幕与音轨'); ui('glass-tracks'); screenshot('glass-tracks'); input_cmd('keyevent 4')
    adb('shell','am','force-stop','com.sweefl.player')
    print('Extended preview checks finished.',flush=True)
