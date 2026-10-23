from codec_matrix import adb, launch, snapshot, OUT
from ui_lab import ui, find, tap, tap_node, screenshot
import json,re,time

results=[]
def record(name,passed,details=None):
    results.append({'check':name,'passed':bool(passed),'details':details})
    (OUT/'interaction-results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
    print(name,passed,details,flush=True)
    if not passed: raise AssertionError(name)
def volume():
    text=adb('shell','cmd','media_session','volume','--stream','3','--get')
    return int(re.search(r'volume is (\d+)',text)[1])
def brightness():
    dump=adb('shell','dumpsys','window','windows')
    matches=re.findall(r'(?:screenBrightness|sbrt)=([0-9.]+)',dump)
    return [float(v) for v in matches]

launch('synthetic/avc1080.mp4',extra=('--ez','keepControls','true'),key='interactions')
tree=ui('interaction-playing'); tap(tree,'暂停'); tree=ui('interaction-paused')
root_bounds=list(map(int,re.findall(r'\d+',next(tree.iter('node')).get('bounds'))))
if root_bounds[2]-root_bounds[0] > root_bounds[3]-root_bounds[1]:
    tap(tree,'切换横竖屏'); tree=ui('interaction-portrait')
start_volume=volume(); start_brightness=brightness()
adb('shell','su','-c','input swipe 1100 1800 1100 900 400')
new_volume=volume(); record('right vertical gesture changes media volume',new_volume>start_volume,{'before':start_volume,'after':new_volume})
adb('shell','su','-c',f'cmd media_session volume --stream 3 --set {start_volume}')
adb('shell','su','-c','input swipe 180 1800 180 900 400')
screenshot('brightness-gesture')
new_brightness=brightness(); record('left vertical gesture changes window brightness',new_brightness!=start_brightness and bool(new_brightness),{'before':start_brightness,'after':new_brightness})
tap(ui('before-lock'),'锁定触控，轻触屏幕后可解锁')
adb('shell','su','-c','input tap 640 900')
tree=ui('locked'); record('lock exposes only unlock control',find(tree,'解锁控制') is not None and find(tree,'播放') is None)
tap(tree,'解锁控制'); tree=ui('unlocked'); record('unlock restores controls',find(tree,'播放') is not None)
# Seek close to the end while paused, then change decoder without history normalization.
slider=next(n for n in tree.iter('node') if n.get('class')=='android.widget.SeekBar')
x1,y1,x2,y2=map(int,re.findall(r'\d+',slider.get('bounds')))
adb('shell','su','-c',f'input tap {int(x1+(x2-x1)*.95)} {(y1+y2)//2}')
time.sleep(3); before=snapshot()
tap(ui('near-end'),'播放信息与解码')
tree=ui('decode-dialog')
if find(tree,'系统软件解码') is None:
    adb('shell','su','-c','input swipe 950 1900 950 1100 400'); tree=ui('decode-dialog-scrolled')
tap(tree,'系统软件解码'); ui('software-restored'); time.sleep(3); after=snapshot()
record('decoder switch preserves near-end paused position',before['positionMs']>=37000 and abs(after['positionMs']-before['positionMs'])<1000 and not after['isPlaying'],{'before':before['positionMs'],'after':after['positionMs'],'decoder':after.get('videoDecoder')})
# Native PiP must continue actual frame output and contain no custom playback controls.
launch('synthetic/avc1080.mp4',extra=('--ez','keepControls','true'),key='pip')
tree=ui('pip-before'); first=snapshot(); tap(tree,'画中画'); time.sleep(3)
second=snapshot(); dump=adb('shell','dumpsys','activity','activities')
pinned='mode=pinned' in dump or 'windowingMode=2' in dump
record('PiP enters pinned mode and keeps rendering',pinned and second.get('renderedFrames',0)>first.get('renderedFrames',0) and second.get('isPlaying'),{'pinned':pinned,'beforeFrames':first.get('renderedFrames'),'afterFrames':second.get('renderedFrames')})
tree=ui('pip-after'); record('PiP hides custom playback controls',find(tree,'字幕与音轨') is None and find(tree,'播放信息与解码') is None)
adb('shell','am','force-stop','com.sweefl.player')
# Capture actual landscape refraction over video.
launch('synthetic/avc1080.mp4',extra=('--ez','keepControls','true','--ez','landscape','true','--ez','glass','true'),key='glass-preview')
ui('glass-layout'); screenshot('landscape-refraction'); first=snapshot(); time.sleep(3); second=snapshot()
record('landscape glass playback keeps rendering',second.get('renderedFrames',0)>first.get('renderedFrames',0) and not second.get('error'))
adb('shell','am','force-stop','com.sweefl.player')
