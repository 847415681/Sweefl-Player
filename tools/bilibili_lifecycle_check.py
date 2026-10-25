"""Guarded Debug Bilibili rotation/background and account-free login checks.

Lead may register ('bilibili_lifecycle_check.py', 'bilibili-lifecycle') in guarded_v032.py.
No device work on import. Requires explicit serial and the guard's preference backup.
No process death is requested: HOME recovery focuses the SAME task, never launches a replacement.
No credentials, logout, cookie changes, or raw WebView/library screenshots are captured.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import re
import shlex
import time
import uuid
import xml.etree.ElementTree as ET

from bilibili_playback_check import bounds, decoded, evidence, find, online, root_touch, scrub

PACKAGE = 'com.sweefl.player'
PLAYER = PACKAGE + '/.PlayerActivity'
LOGIN = PACKAGE + '/.feature.network.BilibiliLoginActivity'
INPUT_VALUE = 'BV1xx411c7mD'


def main():
    backup = os.environ.get('SWEEFL_PREFERENCES_BACKUP')
    if not os.environ.get('SWEEFL_SERIAL') or not backup or not Path(backup).is_file():
        raise SystemExit('Run under guarded_v032.py with explicit SWEEFL_SERIAL and its preferences backup')
    from device_lab import ADB, SERIAL, OUT, adb, snapshot, verify_device

    verify_device()
    run_dir = OUT / ('lifecycle-' + time.strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:6])
    run_dir.mkdir(parents=True, exist_ok=False)
    results = [{'name': 'process recreation', 'status': 'NOT RUN',
                'reason': 'No process kill requested; no PID-change or SavedState reconstruction claim'}]
    last_state = None
    phase = 'setup'
    safe_labels = {'暂停', '播放', '后退 10 秒', '返回媒体库', '打开网络视频',
                   'B站官方登录（可选）', '完成登录', '退出登录', '返回', '取消',
                   '视频 URL / B站分享链接', 'User-Agent', '播放进度', '返回直播', INPUT_VALUE}

    def save():
        (run_dir / 'results.json').write_text(json.dumps(scrub(results), ensure_ascii=False, indent=2), encoding='utf-8')

    def record(name, passed, detail=None):
        results.append({'name': name, 'passed': bool(passed), 'detail': scrub(detail)})
        save()
        if not passed:
            raise AssertionError(name)
        print(name, 'PASS', flush=True)

    def shell(*args):
        output = adb('shell', shlex.join(['su', '-c', shlex.join(args)]))
        if re.search(r'exception|permission\s+(?:denial|denied)|unknown command|error\s*:', output, re.I):
            raise AssertionError('Root shell action rejected: ' + scrub(output))
        return output

    def mark():
        return int(float(adb('shell', 'cat', '/proc/uptime').split()[0]) * 1000)

    def fresh(predicate, after, timeout=75):
        # Same post-action elapsedRealtime fence as the existing Bili acceptance helper.
        nonlocal last_state
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                state = snapshot()
            except (RuntimeError, ValueError):
                time.sleep(.35)
                continue
            if state.get('elapsedRealtimeMs', 0) > after:
                last_state = state
                if predicate(state):
                    return state
            time.sleep(.35)
        raise AssertionError('Fresh diagnostic condition timed out: ' + phase)

    def tree(tag):
        remote = '/data/local/tmp/sweefl-lifecycle-' + uuid.uuid4().hex + '.xml'
        try:
            reply = adb('shell', 'uiautomator', 'dump', remote, timeout=40)
            if 'dumped to:' not in reply:
                raise AssertionError('No fresh UI hierarchy')
            root = ET.fromstring(adb('exec-out', 'cat', remote))
            # Keep raw hierarchy only in memory for assertions. Persist allowlisted app labels,
            # not webpage account contents or media-library filenames.
            sanitized = ET.fromstring(ET.tostring(root))
            for node in sanitized.iter():
                if node.get('class') == 'android.webkit.WebView':
                    for child in list(node):
                        node.remove(child)
            for node in sanitized.iter():
                for key in ('text', 'content-desc', 'resource-id'):
                    value = node.get(key)
                    if value:
                        node.set(key, value if value in safe_labels else '[redacted]')
            ET.ElementTree(sanitized).write(run_dir / (tag + '.xml'), encoding='utf-8', xml_declaration=True)
            return root
        finally:
            assert re.fullmatch(r'/data/local/tmp/sweefl-lifecycle-[0-9a-f]{32}\.xml', remote)
            adb('shell', 'rm', '-f', remote)

    def wait_tree(tag, predicate):
        for attempt in range(5):
            root = tree(tag + '-' + str(attempt))
            if predicate(root):
                return root
            time.sleep(.3)
        raise AssertionError('UI readiness not observed: ' + tag)

    def tap_node(node):
        if node is None:
            raise AssertionError('Expected control missing')
        x1, y1, x2, y2 = bounds(node)
        root_touch(ADB, SERIAL, run_dir / 'input-events.jsonl', 'tap', (x1+x2)//2, (y1+y2)//2)

    def click(label, tag):
        root = wait_tree(tag, lambda t: find(t, label) is not None)
        tap_node(find(root, label))
        return mark()

    def activities():
        return adb('shell', 'dumpsys', 'activity', 'activities')

    def resumed(component):
        # Permit Android's full or shortened class spelling, but require the exact Activity.
        cls = component.split('/', 1)[1]
        full = PACKAGE + cls if cls.startswith('.') else cls
        variants = (PACKAGE + '/' + cls, PACKAGE + '/' + full)
        dump = activities()
        for line in dump.splitlines():
            if ('mResumedActivity' in line or 'topResumedActivity' in line) and any(v in line for v in variants):
                match = re.search(r'\bt(\d+)\b', line)
                if match:
                    return int(match.group(1))
        return None

    def wait_resumed(component):
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            task = resumed(component)
            if task is not None:
                return task
            time.sleep(.3)
        raise AssertionError('Expected Activity did not resume')

    def no_pip(task, tag):
        dump = activities()
        header = next((line for line in dump.splitlines() if
                       re.search(r'Task\{[^\n]*#' + str(task) + r'\b', line) and 'mode=' in line), None)
        if header is None:
            raise AssertionError('Cannot establish task windowing mode from dumpsys')
        mode = re.search(r'\bmode=([a-zA-Z_-]+)', header)
        record(tag + ' not PiP', mode is not None and mode.group(1) != 'pinned',
               {'taskId': task, 'windowingMode': mode.group(1) if mode else None})

    def paused(state):
        return decoded(state, 'live') and not state.get('isPlaying') and online(state).get('intendedPlaying') is False

    def check_target(tag, state, target):
        info = online(state)
        record(tag + ' paused logical target retained', paused(state) and
               abs(info.get('contentPositionMs', 0) - target) <= 1500 and
               info.get('pausedContentMs') is not None and abs(info['pausedContentMs'] - target) <= 1500,
               {'targetMs': target, 'state': evidence(state)})
        (run_dir / (tag + '-diagnostic.json')).write_text(json.dumps(evidence(state), ensure_ascii=False, indent=2), encoding='utf-8')

    original_rotation = shell('wm', 'user-rotation').strip()
    if not re.fullmatch(r'free|lock [0-3]', original_rotation):
        raise AssertionError('Unrecognized rotation state')
    try:
        phase = 'live-launch'
        shell('wm', 'user-rotation', 'lock', '0')
        adb('shell', 'am', 'force-stop', PACKAGE)
        adb('shell', 'am', 'start', '-W', '-f', '0x10008000', '-n', PLAYER,
            '-d', 'https://live.bilibili.com/6', '--es', 'title', 'Bilibili-Lifecycle-QA',
            '--ez', 'diagnostics', 'true', '--ez', 'keepControls', 'true', '--ez', 'benchmark', 'false')
        state = fresh(lambda s: decoded(s, 'live') and s.get('isPlaying'), mark())
        record('room6 live decoded with DVR', online(state).get('seekable') is True and
               online(state)['rangeEndMs'] - online(state)['rangeStartMs'] >= 30_000, evidence(state))
        state = fresh(paused, click('暂停', 'initial-pause'))
        initial_target = online(state)['contentPositionMs']
        phase = 'paused-rewind'
        state = fresh(lambda s: paused(s) and online(s).get('shiftSeconds', 0) > 0,
                      click('后退 10 秒', 'paused-rewind'))
        target = online(state)['contentPositionMs']
        record('paused rewind entered replay by ten seconds', abs(target - (initial_target - 10_000)) <= 1500, evidence(state))
        check_target('before-rotation', state, target)
        task = wait_resumed(PLAYER)
        original_pid = adb('shell', 'pidof', PACKAGE).strip()
        no_pip(task, 'before-rotation')

        phase = 'rotation'
        shell('wm', 'user-rotation', 'lock', '1')
        state = fresh(paused, mark())
        root = wait_tree('rotated-player', lambda t: find(t, '播放') is not None and
                         bounds(next(t.iter('node')))[2] > bounds(next(t.iter('node')))[3])
        record('rotation keeps original task', wait_resumed(PLAYER) == task)
        check_target('after-rotation', state, target)
        no_pip(task, 'after-rotation')

        phase = 'home-background'
        shell('input', 'keyevent', 'KEYCODE_HOME')
        deadline = time.monotonic() + 10
        while resumed(PLAYER) is not None and time.monotonic() < deadline:
            time.sleep(.3)
        record('HOME backgrounds player', resumed(PLAYER) is None)
        no_pip(task, 'background')
        # Focusing an existing task is deliberately different from am start or force-stop/relaunch.
        shell('am', 'task', 'focus', str(task))
        stamp = mark()
        record('return focuses same task', wait_resumed(PLAYER) == task)
        state = fresh(paused, stamp)
        wait_tree('returned-player', lambda t: find(t, '播放') is not None)
        check_target('after-background', state, target)
        no_pip(task, 'after-background')
        record('background test preserved process', adb('shell', 'pidof', PACKAGE).strip() == original_pid,
               {'processRecreationTested': False})
        save()

        phase = 'login-roundtrip'
        shell('wm', 'user-rotation', 'lock', '0')
        click('返回媒体库', 'leave-player')
        adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/.MainActivity')
        main_task = wait_resumed(PACKAGE + '/.MainActivity')
        click('打开网络视频', 'network-entry')
        root = wait_tree('network-field', lambda t: any(n.get('class') == 'android.widget.EditText' for n in t.iter('node')))
        field = next(n for n in root.iter('node') if n.get('class') == 'android.widget.EditText')
        record('new network address starts empty', not field.get('text'))
        tap_node(field)
        shell('input', 'text', INPUT_VALUE)
        wait_tree('network-value', lambda t: any(n.get('class') == 'android.widget.EditText' and
                  n.get('text') == INPUT_VALUE for n in t.iter('node')))
        # Hide only a positively observed IME; a blind BACK could dismiss the network dialog.
        ime_state = adb('shell', 'dumpsys', 'input_method')
        if re.search(r'\b(?:mInputShown|mIsInputViewShown|mInputViewStarted)=true\b', ime_state):
            shell('input', 'keyevent', 'KEYCODE_BACK')
        click('B站官方登录（可选）', 'official-login-entry')
        login_task = wait_resumed(LOGIN)
        record('official login activity in original task', login_task == main_task)
        no_pip(login_task, 'login')
        root = wait_tree('login-toolbar', lambda t: find(t, '完成登录') is not None and any(
                         n.get('class') == 'android.widget.Button' and n.get('text') == '返回' for n in t.iter('node')))
        tap_node(next(n for n in root.iter('node') if n.get('class') == 'android.widget.Button' and n.get('text') == '返回'))
        record('login returns to same MainActivity task', wait_resumed(PACKAGE + '/.MainActivity') == main_task)
        root = wait_tree('login-return-input', lambda t: any(n.get('class') == 'android.widget.EditText' and
                         n.get('text') == INPUT_VALUE for n in t.iter('node')))
        record('login return preserves network input', True, {'syntheticInputRetained': True})
        no_pip(main_task, 'login-return')
        click('取消', 'network-close')
    except Exception as failure:
        results.append({'name': 'harness failure', 'passed': False, 'phase': phase,
                        'error': scrub(str(failure)), 'lastDiagnostic': evidence(last_state) if last_state else None})
        save()
        try:
            tree('failure-' + phase)
        except Exception as capture_error:
            results.append({'name': 'failure hierarchy capture', 'error': scrub(str(capture_error))})
        raise
    finally:
        save()
        shell('wm', 'user-rotation', *original_rotation.split())
        restored = shell('wm', 'user-rotation').strip() == original_rotation
        (run_dir / 'rotation-restoration.json').write_text(json.dumps({'restored': restored, 'original': original_rotation}), encoding='utf-8')
        if not restored:
            raise AssertionError('Rotation restoration mismatch')
    print('Bilibili lifecycle/login checks passed; process recreation NOT RUN.', flush=True)


if __name__ == '__main__':
    main()
