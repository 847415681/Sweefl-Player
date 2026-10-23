"""Shared device-test transport. Set SWEEFL_ADB, SWEEFL_SERIAL and SWEEFL_TEST_OUT.
Only debug Sweefl diagnostics and explicitly named test resources are accessed.
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[1]
_workspace_tools = ROOT.parent / '.android-tools'
ADB = os.environ.get('SWEEFL_ADB') or shutil.which('adb') or str(_workspace_tools / 'sdk/platform-tools/adb.exe')
SERIAL = os.environ.get('SWEEFL_SERIAL', '75HUJNORLVXOD6S8')
OUT = ROOT / os.environ.get('SWEEFL_TEST_OUT', 'docs/testing-v020')
OUT.mkdir(parents=True, exist_ok=True)
if (_workspace_tools / 'tmp').is_dir():
    os.environ['TMP'] = os.environ['TEMP'] = str(_workspace_tools / 'tmp')


def adb(*args, timeout=30):
    process = subprocess.run([ADB, '-s', SERIAL, *args], capture_output=True, text=True,
                             encoding='utf-8', errors='replace', timeout=timeout)
    if process.returncode:
        raise RuntimeError(process.stdout + process.stderr)
    return process.stdout


def verify_device():
    devices = adb('devices', '-l')
    if not any(line.split()[:2] == [SERIAL, 'device'] for line in devices.splitlines()):
        raise RuntimeError(f'Authorized target {SERIAL} is not connected and ready: {devices}')
    return devices


def snapshot():
    return json.loads(adb('exec-out', 'su', '-c', 'cat /data/user/0/com.sweefl.player/files/diagnostics.json'))


def launch(path, mode='AUTO', ua='Sweefl-QA/0.2', extra=(), key='test'):
    verify_device()
    adb('shell', 'am', 'force-stop', 'com.sweefl.player')
    url = 'http://127.0.0.1:8765/require-ua/' + quote(path, safe='/') + '?case=' + key
    adb('shell', 'am', 'start', '-W', '-f', '0x10008000', '-n', 'com.sweefl.player/.PlayerActivity', '-d', url,
        '--es', 'userAgent', ua, '--es', 'decodeMode', mode,
        '--ez', 'diagnostics', 'true', '--ez', 'benchmark', 'true', *extra)
    return url
