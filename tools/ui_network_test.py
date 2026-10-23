from ui_lab import *
import json, shlex

tree=ui('network-before-entry')
fields=[n for n in tree.iter('node') if n.get('class')=='android.widget.EditText']
tap_node(fields[0])
adb('shell','su','-c',"input text 'http://127.0.0.1:8765/require-ua/synthetic/avc1080.mp4?case=ui'")
adb('shell','su','-c','input keyevent 4')
tree=ui('network-url-entered')
fields=[n for n in tree.iter('node') if n.get('class')=='android.widget.EditText']
tap_node(fields[1])
adb('shell','su','-c','input keycombination 113 29')
adb('shell','su','-c',"input text 'Sweefl-QA/0.2'")
adb('shell','su','-c','input keyevent 4')
tree=ui('network-filled')
texts=[n.get('text') for n in tree.iter('node') if n.get('class')=='android.widget.EditText']
assert texts[1]=='Sweefl-QA/0.2',texts
screenshot('network-dialog')
tap(tree,'播放')
tree=ui('network-ui-playing')
assert find(tree,'暂停') is not None,'Network launch did not play'
tap(tree,'暂停')
tree=ui('network-ui-paused')
assert find(tree,'播放') is not None
(OUT/'ui-results.json').write_text(json.dumps([{'check':'URL and custom UA entered through UI; video plays and pauses','passed':True}],ensure_ascii=False,indent=2),encoding='utf-8')
tap(tree,'字幕与音轨')
tree=ui('subtitle-menu')
for n in tree.iter('node'):
    if n.get('text') or n.get('content-desc'): print(n.get('text'),n.get('content-desc'),n.get('bounds'))
