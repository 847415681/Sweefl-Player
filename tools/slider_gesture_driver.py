"""Build/run the standalone UiAutomation Slider helper; no device activity on import.

Local compile only:
  python tools/slider_gesture_driver.py --build-only --output <evidence-directory>
The Bilibili guarded suite imports run_slider; no instrumentation APK/install is needed.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = Path(__file__).parent / 'slider_gesture' / 'SliderGesture.java'


def _java_tools():
    roots = [Path(os.environ[name]) for name in ('JAVA_HOME',) if os.environ.get(name)]
    roots += sorted((ROOT.parent / '.android-tools' / 'jdk').glob('*'), reverse=True)
    extension = '.exe' if os.name == 'nt' else ''
    for root in roots:
        javac, java = root / 'bin' / ('javac' + extension), root / 'bin' / ('java' + extension)
        if javac.is_file() and java.is_file():
            return javac, java
    javac, java = shutil.which('javac'), shutil.which('java')
    if javac and java:
        return Path(javac), Path(java)
    raise RuntimeError('JDK javac/java required for the tools-only driver')


def _sdk():
    roots = [Path(os.environ[name]) for name in ('ANDROID_HOME', 'ANDROID_SDK_ROOT') if os.environ.get(name)]
    roots.append(ROOT.parent / '.android-tools' / 'sdk')
    for root in roots:
        android = root / 'platforms' / 'android-35' / 'android.jar'
        d8 = sorted(root.glob('build-tools/*/lib/d8.jar'), reverse=True)
        if android.is_file() and d8:
            return android, d8[0]
    raise RuntimeError('Android SDK platform35 and Build Tools d8.jar required')


def build_driver(output: Path) -> Path:
    javac, java = _java_tools()
    android, d8 = _sdk()
    identity = hashlib.sha256(SOURCE.read_bytes()).hexdigest()[:16]
    build = output / ('slider-driver-' + identity)
    artifact = build / 'slider-driver.jar'
    if artifact.is_file():
        return artifact
    classes, dex = build / 'classes', build / 'dex'
    classes.mkdir(parents=True, exist_ok=True)
    dex.mkdir(parents=True, exist_ok=True)
    commands = [
        [str(javac), '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-classpath', str(android),
         '-d', str(classes), str(SOURCE)],
    ]
    log = []
    for command in commands:
        result = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', errors='replace')
        log.append({'step': 'javac', 'returnCode': result.returncode, 'stdout': result.stdout, 'stderr': result.stderr})
        (build / 'build.json').write_text(json.dumps(log, indent=2), encoding='utf-8')
        if result.returncode:
            raise RuntimeError('Gesture helper javac failed; inspect driver build.json')
    command = [str(java), '-cp', str(d8), 'com.android.tools.r8.D8', '--lib', str(android),
               '--min-api', '26', '--output', str(dex), *map(str, sorted(classes.rglob('*.class')))]
    result = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', errors='replace')
    log.append({'step': 'd8', 'returnCode': result.returncode, 'stdout': result.stdout, 'stderr': result.stderr})
    (build / 'build.json').write_text(json.dumps(log, indent=2), encoding='utf-8')
    if result.returncode:
        raise RuntimeError('Gesture helper d8 failed; inspect driver build.json')
    with zipfile.ZipFile(artifact, 'w', zipfile.ZIP_DEFLATED) as archive:
        for entry in sorted(dex.glob('classes*.dex')):
            archive.write(entry, entry.name)
    with zipfile.ZipFile(artifact) as archive:
        if 'classes.dex' not in archive.namelist() or archive.testzip() is not None:
            raise RuntimeError('Gesture helper DEX archive is invalid')
    return artifact


def run_slider(adb_path, serial, output: Path, tag: str, fraction: float, duration_ms: int):
    backup = os.environ.get('SWEEFL_PREFERENCES_BACKUP')
    if not serial or serial != os.environ.get('SWEEFL_SERIAL') or not backup or not Path(backup).is_file():
        raise RuntimeError('Explicit guarded serial and preferences backup required')
    if not 0 < fraction < 1 or not 100 <= duration_ms <= 120000 or not re.fullmatch(r'[a-zA-Z0-9_-]+', tag):
        raise ValueError('Invalid bounded gesture request')
    artifact = build_driver(output)
    token = uuid.uuid4().hex
    remote_jar = '/data/local/tmp/sweefl-slider-' + token + '.jar'
    remote_png = '/data/local/tmp/sweefl-slider-' + token + '.png'
    logs = []

    def command(args, timeout=30, check=True):
        try:
            result = subprocess.run([str(adb_path), '-s', serial, *args], capture_output=True,
                                    text=True, encoding='utf-8', errors='replace', timeout=timeout)
        except (OSError, subprocess.TimeoutExpired) as failure:
            logs.append({'step': args[0], 'errorType': type(failure).__name__})
            raise RuntimeError('Gesture transport failed; inspect driver-transport.json') from None
        logs.append({'step': args[0], 'returnCode': result.returncode,
                     'stdout': result.stdout, 'stderr': result.stderr})
        if check and result.returncode:
            raise RuntimeError('Gesture transport rejected command; inspect driver-transport.json')
        return result

    try:
        command(['push', str(artifact), remote_jar])
        # The entire env/app_process command is one safely quoted su -c argument.
        child = shlex.join(['env', 'CLASSPATH=' + remote_jar, 'app_process', '/system/bin',
                            'com.sweefl.tools.SliderGesture', str(fraction), str(duration_ms), remote_png])
        result = command(['shell', shlex.join(['su', '-c', child])], timeout=duration_ms / 1000 + 90, check=False)
        records = [line.removeprefix('SWEEFL_SLIDER_RESULT=') for line in result.stdout.splitlines()
                   if line.startswith('SWEEFL_SLIDER_RESULT=')]
        if not records:
            raise AssertionError('Gesture helper returned no structured result')
        report = json.loads(records[-1])
        (output / (tag + '-driver.json')).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        if result.returncode or not report.get('ok') or not report.get('selectionVerified'):
            raise AssertionError('UiAutomation gesture failed before verified UP; inspect driver evidence')
        measured = report['preUp']['fraction']
        if abs(measured - fraction) > report['selectionToleranceFraction']:
            raise AssertionError('Driver midpoint evidence does not satisfy its tolerance')
        command(['pull', remote_png, str(output / (tag + '-held.png'))])
        return report
    finally:
        for remote in (remote_jar, remote_png):
            if not re.fullmatch(r'/data/local/tmp/sweefl-slider-[a-f0-9]{32}\.(jar|png)', remote):
                raise AssertionError('Unexpected owned driver cleanup path')
            try:
                command(['shell', shlex.join(['su', '-c', shlex.join(['rm', '-f', remote])])], check=False)
            except RuntimeError:
                pass
        (output / (tag + '-driver-transport.json')).write_text(json.dumps(logs, ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-only', action='store_true', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    print(build_driver(args.output).resolve())
