"""Settings observation regression, restoring the original choice.
OEM touch injection can miss a click (also observed on the archived APK); keyboard/Back
fallbacks are recorded and exercise the same application callbacks.
"""
import json
import xml.etree.ElementTree as ET
from device_lab import adb,OUT,verify_device
from ui_lab import ui,tap,tap_node,screenshot,find

verify_device()
adb('shell','am','force-stop','com.sweefl.player')
adb('shell','am','start','-W','-n','com.sweefl.player/.MainActivity')
fallbacks=[]
def open_settings(name):
    tree=ui(name+'-home')
    tap(tree,'设置'); tree=ui(name+'-opened')
    if find(tree,'Sweefl Player') is None:
        # Fresh activity has network then settings as its first keyboard focus targets.
        adb('shell','am','force-stop','com.sweefl.player')
        adb('shell','am','start','-W','-n','com.sweefl.player/.MainActivity')
        ui(name+'-keyboard-ready')
        adb('shell','su','-c','input keyevent 61 61 66')
        tree=ui(name+'-keyboard'); fallbacks.append(name+': keyboard navigation')
    assert find(tree,'Sweefl Player') is not None
    return tree

def close_settings(tree,name):
    tap(tree,'完成'); tree=ui(name)
    if find(tree,'Sweefl Player') is not None:
        adb('shell','su','-c','input keyevent 4'); tree=ui(name+'-back')
        fallbacks.append(name+': Back dismissal')
    assert find(tree,'设置') is not None

def switch(tree):
    nodes=[n for n in tree.iter('node') if n.get('checkable')=='true' and n.get('package')=='com.sweefl.player']
    assert len(nodes)==1,'Expected exactly one settings toggle'
    return nodes[0]
def stored():
    root=ET.fromstring(adb('exec-out','su','-c','cat /data/user/0/com.sweefl.player/shared_prefs/player_preferences.xml'))
    node=root.find("boolean[@name='realtime_glass']")
    return node is None or node.get('value')=='true'

tree=open_settings('settings'); initial=stored(); assert switch(tree).get('checked')==str(initial).lower()
tap_node(switch(tree)); tree=ui('settings-toggled')
assert stored()==(not initial) and switch(tree).get('checked')==str(not initial).lower()
close_settings(tree,'settings-closed'); tree=open_settings('settings-reopen')
assert switch(tree).get('checked')==str(not initial).lower()
tap_node(switch(tree)); tree=ui('settings-restored'); assert stored()==initial
screenshot('settings-refactor'); close_settings(tree,'settings-final-close')
(OUT/'settings-results.json').write_text(json.dumps([
    {'check':'glass setting changes storage and UI together','passed':True,'inputFallbacks':fallbacks},
    {'check':'settings reopen retains changed value','passed':True},
    {'check':'original glass setting restored','passed':True,'value':initial},
],ensure_ascii=False,indent=2),encoding='utf-8')
print('Settings state/store/reopen/restore checks passed',fallbacks,flush=True)
