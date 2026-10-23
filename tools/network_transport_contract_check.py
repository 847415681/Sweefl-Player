"""Run actual Android DataSource contract tests without third-party test frameworks.
Build first: build-local.ps1 -Tasks @(':app:assembleDebug', ':app:assembleDebugAndroidTest')
Install matching debug target with existing guarded_v032.py (preserves user data).
This runner installs ONLY the test APK; it never clears target app data or wakes the screen.
"""
import json,os,subprocess,sys,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
ADB=os.environ.get('SWEEFL_ADB',str(ROOT.parent/'.android-tools/sdk/platform-tools/adb.exe'))
SERIAL=os.environ.get('SWEEFL_SERIAL','')
if not SERIAL:raise SystemExit('Set SWEEFL_SERIAL explicitly')
OUT=ROOT/os.environ.get('SWEEFL_TEST_OUT','docs/testing-v033/network-contracts');OUT.mkdir(parents=True,exist_ok=True)
def adb(*args,timeout=60):
 p=subprocess.run([ADB,'-s',SERIAL,*args],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=timeout)
 if p.returncode:raise RuntimeError(p.stdout+p.stderr)
 return p.stdout
assert any(x.split()[:2]==[SERIAL,'device'] for x in adb('devices','-l').splitlines())
apk=ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
if not apk.is_file():raise SystemExit('Missing test APK: run :app:assembleDebugAndroidTest first')
remote='/data/local/tmp/sweefl-network-contract-'+uuid.uuid4().hex+'.apk'
adb('push',str(apk),remote)
installed=adb('shell','su','-c','pm install -r '+remote)
assert 'Success' in installed,installed
text=adb('shell','am','instrument','-w','-r','com.sweefl.player.test/com.sweefl.player.networktest.NetworkContractInstrumentation',timeout=120)
(OUT/'instrumentation.txt').write_text(text,encoding='utf-8')
print(text)
if 'INSTRUMENTATION_RESULT: result=PASS' not in text:raise SystemExit('Transport contracts failed; see instrumentation.txt')
(OUT/'result.json').write_text(json.dumps({'result':'PASS','serial':SERIAL,'runner':'custom android.app.Instrumentation','productionClass':'ParallelHttpDataSource'},indent=2))
