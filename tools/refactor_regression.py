"""Run the scoped refactor regression on an explicitly configured, authorized device.
Requires the local fixture server on port 8765 and the named Sweefl test media/subtitles.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

parser=argparse.ArgumentParser()
parser.add_argument('--apk',default='app/build/outputs/apk/debug/app-debug.apk')
parser.add_argument('--uri',default='content://media/external/video/media/529')
parser.add_argument('--out',default='docs/testing-refactor')
parser.add_argument('--resume',action='store_true',help='Reuse passing evidence only when installed APK hash matches')
options=parser.parse_args()
os.environ['SWEEFL_TEST_OUT']=options.out
from device_lab import adb,ROOT,OUT,ADB,SERIAL,verify_device
from ui_lab import ui,tap

print(verify_device(),flush=True)
apk=(ROOT/options.apk).resolve(); assert apk.is_file(),apk
assert re.fullmatch(r'content://media/(external|external_primary)/video/media/\d+',options.uri),options.uri
name=adb('shell','su','-c',f'content query --uri {options.uri} --projection _display_name')
assert 'Sweefl-UI-Test-01.mp4' in name,'The selected URI must refer to the project-owned local test movie'
adb('shell','am','force-stop','com.sweefl.player')
prefs='/data/user/0/com.sweefl.player/shared_prefs/'
def read_pref(name): return adb('exec-out','su','-c','cat '+prefs+name+'.xml')
def pref_hashes():
    return {name:hashlib.sha256(read_pref(name).encode()).hexdigest() for name in ('playback_history','playback_config','player_preferences')}
original=ET.fromstring(read_pref('player_preferences'))
initial_volume=int(re.search(r'volume is (\d+)',adb('shell','cmd','media_session','volume','--stream','3','--get'))[1])
before=pref_hashes(); created_reverse=False
results={'initialVolume':initial_volume,'preferencesBeforeInstall':before}

def run(script,*args):
    print('RUN',script,flush=True)
    subprocess.run([sys.executable,str(ROOT/'tools'/script),*args],cwd=ROOT,check=True,env=os.environ.copy())

def require_all(name,count):
    rows=json.loads((OUT/name).read_text(encoding='utf-8'))
    assert len(rows)==count and all(row['passed'] for row in rows),(name,rows)

def passed(name,count):
    if not (OUT/name).exists(): return False
    rows=json.loads((OUT/name).read_text(encoding='utf-8'))
    return len(rows)==count and all(row['passed'] for row in rows)

def step(script,name,count,*args):
    if not (options.resume and passed(name,count)): run(script,*args)
    require_all(name,count)

try:
    if not options.resume:
        adb('push',str(apk),'/data/local/tmp/sweefl-player-refactor-debug.apk')
        assert 'Success' in adb('shell','su','-c','pm install -r /data/local/tmp/sweefl-player-refactor-debug.apk',timeout=60)
    after=pref_hashes(); assert before==after,'Upgrade changed existing preferences'
    installed=adb('shell','pm','path','com.sweefl.player').strip().removeprefix('package:')
    assert installed.startswith('/data/app/') and installed.endswith('/base.apk')
    installed_sha=adb('shell','su','-c','sha256sum '+installed).split()[0].upper()
    assert installed_sha==hashlib.sha256(apk.read_bytes()).hexdigest().upper()
    if options.resume:
        previous=json.loads((OUT/'upgrade-compatibility.json').read_text(encoding='utf-8'))
        assert previous['installedApkSha256']==installed_sha and previous['preferencesPreserved']
        results.update(previous)
    else:
        results.update(preferencesAfterInstall=after,preferencesPreserved=True,installedApkSha256=installed_sha)
        (OUT/'upgrade-compatibility.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
    mappings=[line.split()[-2:] for line in adb('reverse','--list').splitlines() if line.strip()]
    existing=next((mapping for mapping in mappings if mapping[0]=='tcp:8765'),None)
    if existing is None:
        adb('reverse','tcp:8765','tcp:8765'); created_reverse=True
    else:
        assert existing==['tcp:8765','tcp:8765'],'Do not replace an unrelated ADB mapping'
    adb('shell','su','-c','cmd media_session volume --stream 3 --set 0')
    step('settings_ui_test.py','settings-results.json',3)
    if not (options.resume and passed('ui-results.json',1)):
        adb('shell','am','start','-W','-n','com.sweefl.player/.MainActivity')
        tap(ui('before-network-dialog'),'打开网络视频')
        run('ui_network_test.py')
    require_all('ui-results.json',1)
    if not (options.resume and (OUT/'network-extended.json').exists()): run('network_extended_test.py')
    network=json.loads((OUT/'network-extended.json').read_text(encoding='utf-8'))
    assert len(network)==6
    assert all(row['passed'] for row in network if not row['case'].startswith('HTTPS'))
    https=next(row for row in network if row['case'].startswith('HTTPS'))
    if not https['passed']:
        if not (options.resume and passed('network-https-retry.json',1)):
            run('network_extended_test.py','--only','HTTPS','--output','network-https-retry.json')
        https=json.loads((OUT/'network-https-retry.json').read_text(encoding='utf-8'))[0]
    if not https['passed']:
        run('network_extended_test.py','--fallback-https','--output','network-https-alternate.json')
        https=json.loads((OUT/'network-https-alternate.json').read_text(encoding='utf-8'))[0]
    results['publicHttpsPassed']=https['passed']
    results['publicHttpsError']=https.get('snapshot',{}).get('error')
    step('subtitle_ui_test.py','subtitle-results.json',3)
    step('interaction_test.py','interaction-results.json',8)
    step('extra_ui_test.py','extra-ui.json',3)
    if not (options.resume and (OUT/'format-matrix.json').exists()): run('codec_matrix.py')
    matrix=json.loads((OUT/'format-matrix.json').read_text(encoding='utf-8'))
    assert len(matrix)==29
    unexpected=[x for x in matrix if not x['passed'] and x['name']!='HEVC Main10 software']
    assert not unexpected,unexpected
    known=next(x for x in matrix if x['name']=='HEVC Main10 software')
    known_snapshot=known.get('snapshot',{})
    known_surface_failure=(known_snapshot.get('error')=='ERROR_CODE_FAILED_RUNTIME_CHECK' and
        'setSurface()' in known_snapshot.get('errorDetail','') and 'Released state' in known_snapshot.get('errorDetail',''))
    assert known['passed'] or known_snapshot.get('error')=='ERROR_CODE_DECODING_FAILED' or known_surface_failure,known
    run('device_smoke.py','--adb',ADB,'--serial',SERIAL,'--uri',options.uri,'--out',str(OUT/'baseline'))
    require_all('baseline/results.json',7)
    results['regressionsPassed']=True
finally:
    # Restore only preference keys exercised by this suite, preserving all other current settings.
    adb('shell','am','force-stop','com.sweefl.player')
    current=ET.fromstring(read_pref('player_preferences'))
    for key in ('default_user_agent','realtime_glass'):
        for node in list(current):
            if node.get('name')==key: current.remove(node)
        old=next((node for node in original if node.get('name')==key),None)
        if old is not None: current.append(old)
    restored=OUT/'restore-settings.tmp.xml'
    ET.ElementTree(current).write(restored,encoding='utf-8',xml_declaration=True)
    adb('push',str(restored),'/data/local/tmp/sweefl-refactor-settings.xml')
    adb('shell','su','-c','cp /data/local/tmp/sweefl-refactor-settings.xml '+prefs+'player_preferences.xml')
    # This exact generated temporary file is not part of the public evidence bundle.
    assert restored.resolve()==(OUT/'restore-settings.tmp.xml').resolve() and restored.parent==OUT
    restored.unlink()
    adb('shell','su','-c',f'cmd media_session volume --stream 3 --set {initial_volume}')
    restored_volume=int(re.search(r'volume is (\d+)',adb('shell','cmd','media_session','volume','--stream','3','--get'))[1])
    assert restored_volume==initial_volume
    if created_reverse: adb('reverse','--remove','tcp:8765')
    adb('shell','am','start','-W','-n','com.sweefl.player/.MainActivity')
    verified=ET.fromstring(read_pref('player_preferences'))
    def setting_value(tree,key):
        node=next((item for item in tree if item.get('name')==key),None)
        return None if node is None else (node.tag,node.get('value'),node.text)
    assert all(setting_value(original,key)==setting_value(verified,key) for key in ('default_user_agent','realtime_glass'))
    results.update(initialVolume=initial_volume,restoredVolume=restored_volume,testPreferencesRestored=True,ownedReverseRemoved=created_reverse)
    (OUT/'regression-summary.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
print('Refactor integration regression completed',flush=True)
