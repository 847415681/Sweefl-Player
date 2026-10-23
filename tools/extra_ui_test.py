from codec_matrix import launch,snapshot,OUT,adb
from ui_lab import ui,find,tap,screenshot,find_test_document
import json,time
results=[]
def record(name,ok,detail):
    results.append({'check':name,'passed':bool(ok),'details':detail})
    (OUT/'extra-ui.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
    print(name,ok,flush=True)
    assert ok,name
launch('media3/mp4__hdr10-720p.mp4','SOFTWARE',extra=('--ez','keepControls','true'),key='recover-software')
time.sleep(5); failed=snapshot(); tree=ui('software-failure')
assert failed.get('error'),failed
for attempt in range(3):
    tap(tree,'改用自动解码'); tree=ui('automatic-recovery-'+str(attempt)); time.sleep(3); recovered=snapshot()
    if recovered.get('decodeMode')=='AUTO': break
record('software failure recovers through automatic decode button',not recovered.get('error') and recovered.get('renderedFrames',0)>0 and recovered.get('decodeMode')=='AUTO',{'before':failed,'after':recovered,'inputAttempts':attempt+1})
launch('synthetic/avc-aac-srt.mkv',extra=('--ez','keepControls','true'),key='subtitle-priority')
tap(ui('embedded-playing'),'暂停'); tap(ui('embedded-paused'),'字幕与音轨'); tap(ui('embedded-tracks'),'加载外置字幕（SRT / VTT / ASS）')
tree=ui('priority-picker'); name='Sweefl-test.vtt'
if find(tree,name) is None:
    if find(tree,'浏览') is not None: tap(tree,'浏览'); tree=ui('priority-browse')
    if find(tree,'Download') is not None: tap(tree,'Download'); tree=ui('priority-download')
tree=find_test_document(tree,name,'priority')
tap(tree,name); tree=ui('priority-picked')
if find(tree,'确定') is not None: tap(tree,'确定')
tree=ui('priority-loaded')
if find(tree,'播放') is not None: tap(tree,'播放')
ui('priority-running'); time.sleep(2); sample=snapshot(); screenshot('external-over-embedded')
tracks=sample.get('textTracks',[])
record('chosen external subtitle overrides embedded default subtitle',len(tracks)>=2 and sum(t['selected'] for t in tracks)==1 and any(t['selected'] and name in t['format'] for t in tracks) and sample.get('cueCount',0)>0,tracks)
tap(ui('priority-playing'),'暂停'); tap(ui('priority-paused'),'字幕与音轨'); tap(ui('priority-remove-menu'),'移除外置字幕')
ui('priority-removed'); time.sleep(3); sample=snapshot()
record('removing external subtitle removes its track',all('sweefl-external' not in t['format'] for t in sample.get('textTracks',[])),sample.get('textTracks'))
adb('shell','am','force-stop','com.sweefl.player')
