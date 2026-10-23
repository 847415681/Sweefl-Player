"""Run existing UI regressions while preserving all three preference files and media volume.
Requires the owned fixture server on 8765. Creates/removes a reverse only when absent.
"""
import hashlib,json,os,re,shlex,subprocess,sys,uuid
from pathlib import Path
import xml.etree.ElementTree as ET
os.environ.setdefault('SWEEFL_TEST_OUT','docs/testing-v032')
from device_lab import ROOT,OUT,adb,verify_device
from ui_lab import ui,screenshot
verify_device()
root=lambda c:adb('shell','su -c '+shlex.quote(c))
state=ROOT.parent/'.android-tools/tmp'/('glass-state-'+uuid.uuid4().hex[:12]);state.mkdir()
names=('playback_history','playback_config','player_preferences')
remote_dir='/data/user/0/com.sweefl.player/shared_prefs/'
volume=int(re.search(r'volume is (\d+)',adb('shell','cmd','media_session','volume','--stream','3','--get'))[1])
root('input motionevent CANCEL 0 0');adb('shell','am','force-stop','com.sweefl.player')
rotation=root('wm user-rotation').strip();assert re.fullmatch(r'free|lock [0-3]',rotation)
original={};owners={}
for name in names:
    path=remote_dir+name+'.xml'
    original[name]=adb('exec-out','su','-c','cat '+path)
    owners[name]=root('stat -c %u:%g '+path).strip();assert re.fullmatch(r'\d+:\d+',owners[name])
    (state/(name+'.xml')).write_text(original[name],encoding='utf-8')
os.environ['SWEEFL_PREFERENCES_BACKUP']=str(state/'player_preferences.xml')
reverse=adb('reverse','--list');owned='tcp:8765' not in reverse
if owned:adb('reverse','tcp:8765','tcp:8765')
else:assert any('tcp:8765 tcp:8765' in row for row in reverse.splitlines()),reverse

def put(name,source):
    remote='/data/local/tmp/sweefl-031-'+name+'.xml';dest=remote_dir+name+'.xml'
    adb('push',str(source),remote);root(f'cp {remote} {dest}')
    root(f'if [ -f {dest}.bak ]; then cp {dest} {dest}.bak; fi')
    assert root('stat -c %u:%g '+dest).strip()==owners[name]

def defaults():
    adb('shell','am','force-stop','com.sweefl.player')
    tree=ET.fromstring(original['player_preferences'])
    keys={'realtime_glass','seek_preview','double_tap_left','double_tap_center','double_tap_right'}
    if 'SWEEFL_TEST_BLUR' in os.environ: keys.add('glass_blur_amount')
    if 'SWEEFL_TEST_THREADS' in os.environ: keys.add('network_threads')
    for node in list(tree):
        if node.get('name') in keys:tree.remove(node)
    for key in ('realtime_glass','seek_preview'):ET.SubElement(tree,'boolean',name=key,value='true')
    if 'SWEEFL_TEST_BLUR' in os.environ: ET.SubElement(tree,'int',name='glass_blur_amount',value=str(int(os.environ['SWEEFL_TEST_BLUR'])))
    if 'SWEEFL_TEST_THREADS' in os.environ: ET.SubElement(tree,'int',name='network_threads',value=str(int(os.environ['SWEEFL_TEST_THREADS'])))
    p=state/'test-preferences.xml';p.write_text(ET.tostring(tree,encoding='unicode'),encoding='utf-8');put('player_preferences',p)

completed=[]
try:
    apk=ROOT/os.environ.get('SWEEFL_APK','app/build/outputs/apk/debug/app-debug.apk');digest=hashlib.sha256(apk.read_bytes()).hexdigest()
    if os.environ.get('SWEEFL_SKIP_INSTALL')!='1':
        adb('push',str(apk),'/data/local/tmp/sweefl-032.apk')
        assert 'Success' in root('pm install -r /data/local/tmp/sweefl-032.apk')
    else:
        current=adb('shell','pm','path','com.sweefl.player').strip().removeprefix('package:')
        digest=root('sha256sum '+shlex.quote(current)).split()[0]
    preserved={name:adb('exec-out','su','-c','cat '+remote_dir+name+'.xml')==original[name] for name in names};assert all(preserved.values())
    (OUT/'installation.json').write_text(json.dumps({'sha256':digest,'preferencesPreserved':preserved},indent=2))
    defaults();adb('shell','am','start','-W','-f','0x10008000','-n','com.sweefl.player/.MainActivity');ui('library');screenshot('library')
    for script,folder in [('glass_dialog_checks.py','dialogs'),('interaction_test.py','legacy'),('glass_gesture_regression.py','gestures'),('glass_keyboard_landscape.py','keyboard'),('glass_alignment_check.py','alignment'),('glass_navigation_refresh_check.py','navigation-refresh'),('glass_motion_baseline.py','baseline'),('hold_speed_check.py','hold'),('immersive_preview_check.py','immersive'),('preview_flow_check.py','preview-flow'),('glass_blur_check.py','blur'),('subtitle_ui_test.py','subtitles-basic'),('subtitle_sync_check.py','subtitle-sync'),('rendering_check.py','rendering'),('ffmpeg_check.py','ffmpeg'),('rendering_controls_check.py','render-controls'),('rendering_extremes_check.py','render-extremes'),('seek_epoch_check.py','seek-epoch'),('glass_source_cadence_check.py','source-cadence'),('surface_recreation_check.py','surface-recreation'),('glass_transition_capture.py','transition'),('dialog_backdrop_check.py','dialog-backdrop'),('retry_recovery_check.py','retry'),('release_smoke_check.py','release-smoke'),('glass_final_visual.py','final-visual'),('dialog_performance_check.py','dialog-perf'),('parallel_playback_check.py','parallel'),('library_floating_check.py','library-floating'),('network_settings_check.py','network-settings')]:
        if folder not in os.environ.get('SWEEFL_UI_SUITES','dialogs,legacy,gestures,keyboard').split(','): continue
        defaults()
        env=os.environ.copy();env['SWEEFL_TEST_OUT']=str(OUT/folder)
        subprocess.run([sys.executable,str(ROOT/'tools'/script)],env=env,cwd=ROOT,check=True)
        completed.append(folder)
finally:
    root('input motionevent CANCEL 0 0');adb('shell','am','force-stop','com.sweefl.player')
    root(f'cmd media_session volume --stream 3 --set {volume}')
    root('wm user-rotation '+rotation);assert root('wm user-rotation').strip()==rotation
    for name in names:put(name,state/(name+'.xml'))
    restored={name:adb('exec-out','su','-c','cat '+remote_dir+name+'.xml')==original[name] for name in names};assert all(restored.values())
    assert int(re.search(r'volume is (\d+)',adb('shell','cmd','media_session','volume','--stream','3','--get'))[1])==volume
    if owned:adb('reverse','--remove','tcp:8765')
    (OUT/'restoration.json').write_text(json.dumps({'preferencesRestored':restored,'volumeRestored':volume,'ownedReverseRemoved':owned,'completedSuites':completed},indent=2))
    adb('shell','am','start','-W','-f','0x10008000','-n','com.sweefl.player/.MainActivity')
