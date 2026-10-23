"""Navigation and refresh-mode checks. Run under the parent preference/rotation guard."""
import json,re,shlex,time,os
from device_lab import adb,OUT,launch,verify_device
from ui_lab import ui,find,tap,tap_node,screenshot
from glass_gesture_regression import record,show,bounds,preferences,input_cmd
from glass_motion_baseline import measure
verify_device();root=lambda c:adb('shell','su -c '+shlex.quote(c))

def main_page():
    input_cmd('motionevent CANCEL 0 0');adb('shell','am','force-stop','com.sweefl.player')
    adb('shell','am','start','-W','-f','0x10008000','-n','com.sweefl.player/.MainActivity');return ui('main')
def tab_node(tree,label):
    return max((n for n in tree.iter('node') if n.get('text')==label),key=lambda n:bounds(n)[3])
def selected(tree,node):
    parents={c:p for p in tree.iter() for c in p}
    while node is not None:
        if node.get('selected')=='true':return True
        node=parents.get(node)
    return False

def thread_count():
    pid=adb('shell','pidof','com.sweefl.player').strip().split()[0]
    return sum('SweeflGlassCopy' in s for s in root(f'ps -T -p {pid} -o CMD').splitlines())

if not os.environ.get('SWEEFL_LIFECYCLE_ONLY'):
    root('wm user-rotation lock 0');tree=main_page();screenshot('navigation-videos')
    x1,y1,x2,y2=bounds(next(tree.iter('node')));open_node=find(tree,'打开文件');bx1,by1,bx2,by2=bounds(open_node)
    record('open file action is in the upper right',bx1>x2*.5 and by2<y2*.3)
    icons=[bounds(find(tree,label)) for label in ['打开文件','打开网络视频','设置']]
    record('header uses equal icons with file open first',icons[0][0]<icons[1][0]<icons[2][0] and len({(v[2]-v[0],v[3]-v[1]) for v in icons})==1)
    record('video tab initially selected',selected(tree,tab_node(tree,'视频')))
    tap_node(tab_node(tree,'文件夹'));tree=ui('folders');screenshot('navigation-folders')
    record('folder tab changes selection',selected(tree,tab_node(tree,'文件夹')) and not selected(tree,tab_node(tree,'视频')))
    tap_node(tab_node(tree,'视频'));tree=ui('videos-return');record('video tab restores selection',selected(tree,tab_node(tree,'视频')))
    tap(tree,'打开文件');picker=ui('file-picker')
    record('upper right open launches document picker',any('documentsui' in n.get('package','') or n.get('package')=='com.android.fileexplorer' for n in picker.iter('node')))
    input_cmd('keyevent 4');tree=ui('picker-return');record('picker cancellation returns to library',find(tree,'SWEEFL') is not None)
    root('wm user-rotation lock 1');tree=ui('main-landscape');screenshot('navigation-landscape')
    record('landscape keeps both tabs and open reachable',all(find(tree,s) is not None for s in ['视频','文件夹','打开文件']))
    root('wm user-rotation lock 0')
    
    rates=[]
    for enum,label in [('FPS_30','30 fps'),('FPS_60','60 fps'),('FPS_90','90 fps'),('DISPLAY','跟随屏幕刷新率')]:
        tree=main_page();tap(tree,'设置');tap(show('玻璃背景刷新率','refresh-entry-'+enum),'玻璃背景刷新率')
        tree=show(label,'refresh-choice-'+enum);tap(tree,label);ui('refresh-selected-'+enum)
        record(enum+' persists from settings',preferences().get('glass_refresh_mode')==enum)
        if enum=='FPS_60':screenshot('refresh-settings')
        launch('synthetic/avc1080.mp4',extra=('--ez','keepControls','true'),key='refresh-'+enum)
        tree=ui('player-'+enum);a,b,c,d=bounds(next(tree.iter('node')))
        if c-a<d-b:tap(tree,'切换横竖屏');ui('player-landscape-'+enum)
        data=measure(enum)
        log=(OUT/(enum+'-glass.log')).read_text(encoding='utf-8')
        rows=[]
        for line in log.splitlines():
            m=re.search(r'mode='+enum+r' displayHz=([\d.]+) playing=true samples=(\d+) seconds=([\d.]+) fps=([\d.]+) copyMeanMs=([\d.]+) copyMaxMs=([\d.]+)',line)
            if m:rows.append(dict(zip(['displayHz','samples','seconds','fps','copyMeanMs','copyMaxMs'],map(float,m.groups()))))
        rows=rows[-4:]
        record(enum+' emits active refresh measurements',len(rows)>=2)
        fps=sum(v['samples'] for v in rows)/sum(v['seconds'] for v in rows)
        display=rows[-1]['displayHz'];target=min(float(enum[4:]) if enum.startswith('FPS_') else display,display)
        record(enum+' reaches requested effective cadence',fps>=target*.72,{'fps':fps,'target':target,'display':display})
        record(enum+' keeps primary video rendering',data['primaryRenderedFrames']>180 and data['primaryDroppedFrames']<=5,{'rendered':data['primaryRenderedFrames'],'dropped':data['primaryDroppedFrames']})
        rates.append({'mode':enum,'fps':fps,'target':target,'rows':rows,'uiRenderCadenceFps':data['uiRenderCadenceFps']})
        (OUT/'refresh-summary.json').write_text(json.dumps(rates,indent=2),encoding='utf-8')
        tree=ui('details-entry-'+enum);tap(tree,'播放信息与解码');tree=show('玻璃背景刷新率','player-rate-'+enum)
        record(enum+' is reflected in player settings after process restart',find(tree,label) is not None)
        tap(show('完成','details-done-'+enum),'完成')
    
else:
    launch('synthetic/avc1080.mp4',extra=('--ez','keepControls','true'),key='capture-lifecycle')
    tree=ui('lifecycle-orientation');a,b,c,d=bounds(next(tree.iter('node')))
    if c-a<d-b:tap(tree,'切换横竖屏');ui('lifecycle-landscape')
    time.sleep(1)

record('one capture worker while visible',thread_count()==1)
tree=ui('pause-for-lifecycle');tap(tree,'暂停');ui('paused')
input_cmd('tap 280 640');time.sleep(1)
record('hidden controls stop capture worker',thread_count()==0)
input_cmd('tap 280 640');time.sleep(1);record('showing controls restarts one worker',thread_count()==1)
tap(ui('toggle-glass-entry'),'播放信息与解码');tree=show('实时折射','toggle-glass')
switch=next(n for n in tree.iter('node') if n.get('checkable')=='true');tap_node(switch);off=ui('glass-off')
record('glass switch is actually disabled',next(n for n in off.iter('node') if n.get('checkable')=='true').get('checked')=='false')
record('disabling glass releases worker',thread_count()==0)
tree=ui('glass-enable');tap_node(next(n for n in tree.iter('node') if n.get('checkable')=='true'));ui('glass-on')
record('re-enabling glass creates one worker',thread_count()==1)
tap(show('完成','details-done'),'完成');input_cmd('keyevent 3');time.sleep(1)
record('background releases capture worker',thread_count()==0)
print('Navigation and refresh checks finished.',flush=True)
