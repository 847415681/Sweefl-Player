"""Targeted smoke test. Requires an unlocked, authorized ARM64 phone and test URI.
Usage: python tools/device_smoke.py --adb PATH --serial SERIAL --uri content://...
Only exercises Sweefl Player and reads its own test playback preferences.
"""
import argparse
import json
import re
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', required=True)
parser.add_argument('--uri', required=True)
parser.add_argument('--out', default='docs/device-test')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
out = root / args.out
out.mkdir(parents=True, exist_ok=True)
results = []

def adb(*values):
    proc = subprocess.run([args.adb, '-s', args.serial, *values], capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=30)
    if proc.returncode:
        raise RuntimeError(proc.stdout + proc.stderr)
    return proc.stdout

def ui(name):
    adb('shell', 'uiautomator', 'dump', '/data/local/tmp/sweefl-smoke.xml')
    path = out / f'{name}.xml'
    adb('pull', '/data/local/tmp/sweefl-smoke.xml', str(path))
    return ET.parse(path).getroot()

def find(tree, label):
    return next((n for n in tree.iter('node') if n.get('content-desc') == label or n.get('text') == label), None)

def tap(tree, label):
    node = find(tree, label)
    if node is None:
        raise AssertionError(f'Missing UI control: {label}')
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'su', '-c', f'input tap {(x1+x2)//2} {(y1+y2)//2}')

def record(name, ok, details=''):
    results.append({'check': name, 'passed': bool(ok), 'details': details})
    (out / 'results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
    if not ok:
        raise AssertionError(name + ': ' + details)

adb('shell', 'am', 'force-stop', 'com.sweefl.player')
adb('shell', 'am', 'start', '-W', '-n', 'com.sweefl.player/.PlayerActivity', '-a', 'android.intent.action.VIEW', '-d', args.uri, '-t', 'video/mp4', '-f', '1')
tree = ui('playing')
record('video reaches playing state', find(tree, '暂停') is not None)
tap(tree, '暂停')
tree = ui('paused')
record('pause changes control to play', find(tree, '播放') is not None)
for _ in range(3):
    tap(tree, '后退 10 秒')
tree = ui('rewound')
tap(tree, '1.0×')
tree = ui('speed-menu')
tap(tree, '1.5×')
tree = ui('speed-selected')
record('speed selection', find(tree, '1.5×') is not None)
tap(tree, '切换横竖屏')
tree = ui('landscape')
record('landscape rotation', tree.get('rotation') in ('1', '3'), tree.get('rotation', 'missing'))
adb('shell', 'screencap', '-p', '/data/local/tmp/sweefl-landscape.png')
adb('pull', '/data/local/tmp/sweefl-landscape.png', str(out / 'landscape.png'))
tap(tree, '切换横竖屏')
tree = ui('portrait-restored')
record('portrait rotation', tree.get('rotation') in ('0', '2'), tree.get('rotation', 'missing'))
# Seek while paused so the saved position cannot race to the end of the short clip.
tap(tree, '快进 10 秒')
tree = ui('seeked')
tap(tree, '返回媒体库')
ui('after-back')
adb('shell', 'su', '-c', 'cp /data/user/0/com.sweefl.player/shared_prefs/playback_history.xml /data/local/tmp/sweefl-history.xml')
adb('shell', 'su', '-c', 'chmod 644 /data/local/tmp/sweefl-history.xml')
adb('pull', '/data/local/tmp/sweefl-history.xml', str(out / 'history.xml'))
history = ET.parse(out / 'history.xml').getroot()
positions = [int(n.get('value')) for n in history.findall('long') if n.get('name', '').startswith('position_')]
record('playback bookmark persisted', any(9000 <= p < 21000 for p in positions), str(positions))
adb('shell', 'am', 'start', '-W', '-n', 'com.sweefl.player/.PlayerActivity', '-a', 'android.intent.action.VIEW', '-d', args.uri, '-t', 'video/mp4', '-f', '1')
tree = ui('resumed')
times = [n.get('text') for n in tree.iter('node') if re.fullmatch(r'\d+:\d{2}', n.get('text', ''))]
record('reopen resumes past bookmark', any(10 <= int(t.split(':')[0])*60+int(t.split(':')[1]) < 23 for t in times), str(times))
if find(tree, '暂停') is not None:
    tap(tree, '暂停')
adb('shell', 'am', 'start', '-W', '-n', 'com.sweefl.player/.MainActivity')
print(json.dumps(results, ensure_ascii=False, indent=2))
