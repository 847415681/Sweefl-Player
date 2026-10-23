from codec_matrix import adb,ROOT,OUT
from pathlib import Path
import hashlib,json,xml.etree.ElementTree as ET
print(adb('devices','-l'))
adb('shell','am','force-stop','com.sweefl.player')
raw=adb('exec-out','su','-c','cat /data/user/0/com.sweefl.player/shared_prefs/player_preferences.xml')
prefs=ET.fromstring(raw); restored=False
for node in prefs.findall('string'):
    if node.get('name')=='default_user_agent' and node.text=='Sweefl-QA/0.2':
        node.text='SweeflPlayer/0.2.0 (Android)'; restored=True
if restored:
    temp=Path(r'D:\Ai_Projects\.android-tools\tmp\sweefl-prefs-restored.xml')
    ET.ElementTree(prefs).write(temp,encoding='utf-8',xml_declaration=True)
    adb('push',str(temp),'/data/local/tmp/sweefl-prefs-restored.xml')
    adb('shell','su','-c','cp /data/local/tmp/sweefl-prefs-restored.xml /data/user/0/com.sweefl.player/shared_prefs/player_preferences.xml')
adb('shell','su','-c','cmd media_session volume --stream 3 --set 35')
volume=adb('shell','cmd','media_session','volume','--stream','3','--get')
assert 'volume is 35' in volume,volume
path=adb('shell','pm','path','com.sweefl.player').strip().removeprefix('package:')
assert path.startswith('/data/app/') and path.endswith('/base.apk'),path
installed=adb('shell','su','-c','sha256sum '+path).split()[0].upper()
expected=hashlib.sha256((ROOT/'dist/Sweefl-Player-0.2.0-arm64-debug.apk').read_bytes()).hexdigest().upper()
assert installed==expected,(installed,expected)
caps=adb('exec-out','su','-c','cat /data/user/0/com.sweefl.player/files/capabilities.json')
json.loads(caps); (OUT/'capabilities-final.json').write_text(caps,encoding='utf-8')
adb('reverse','--remove','tcp:8765')
adb('shell','am','start','-W','-n','com.sweefl.player/.MainActivity')
(OUT/'cleanup.json').write_text(json.dumps({'testDefaultUserAgentRestored':restored,'mediaVolumeRestored':35,'installedApkSha256':installed,'adbReverse8765Removed':True,'userMediaDeleted':False},indent=2),encoding='utf-8')
print('Installed APK hash matches; media volume restored to 35; test default UA restored:',restored)
