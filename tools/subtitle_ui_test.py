from codec_matrix import launch, snapshot
from ui_lab import *
import json, time

results=[]
launch('synthetic/avc1080.mp4',extra=('--ez','keepControls','true'),key='subtitles')
tree=ui('subtitles-player'); tap(tree,'暂停')
for extension in ['srt','vtt','ass']:
    tree=ui('before-'+extension); tap(tree,'字幕与音轨')
    tap(ui('menu-'+extension),'加载外置字幕（SRT / VTT / ASS）')
    tree=ui('picker-'+extension)
    name='Sweefl-test.'+extension
    if find(tree,name) is None:
        if find(tree,'浏览') is not None: tap(tree,'浏览'); tree=ui('browse-'+extension)
        if find(tree,'Download') is not None: tap(tree,'Download'); tree=ui('download-'+extension)
    tap(tree,name)
    tree=ui('picked-'+extension)
    if find(tree,'确定') is not None: tap(tree,'确定')
    tree=ui('loaded-'+extension)
    if find(tree,'播放') is not None: tap(tree,'播放')
    tree=ui('running-'+extension)
    time.sleep(2)
    sample=snapshot()
    screenshot('external-'+extension)
    tree=ui('after-render-'+extension)
    if find(tree,'暂停') is not None: tap(tree,'暂停')
    tap(ui('pause-'+extension),'字幕与音轨')
    tree=ui('selection-'+extension)
    selected=find(tree,'✓ 字幕 · '+name) is not None
    row={'format':extension,'selected':selected,'cueCount':sample.get('cueCount'),'snapshot':sample,'passed':selected and sample.get('cueCount',0)>0}
    results.append(row); (OUT/'subtitle-results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
    print(extension,selected,'cues',sample.get('cueCount'),sample.get('error'),flush=True)
    tap(tree,'完成')
assert len(results)==3 and all(row['passed'] for row in results), results
