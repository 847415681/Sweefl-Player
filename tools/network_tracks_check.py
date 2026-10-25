"""Guarded Debug-device regression for HLS/DASH quality and audio selectors.

Lead runs via guarded_v032.py after adding ('network_tracks_check.py', 'network-tracks').
Requires generate_online_fixture.py outputs served by fixture_server on port 8765.
This module has no device side effects when imported; the generator is entirely separate.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import re
import time


def main() -> None:
    backup = os.environ.get('SWEEFL_PREFERENCES_BACKUP')
    if not os.environ.get('SWEEFL_SERIAL') or not backup or not Path(backup).is_file():
        raise SystemExit('Run under guarded_v032.py with explicit SWEEFL_SERIAL and its preferences backup')
    from device_lab import adb, launch, snapshot, verify_device, OUT
    from ui_lab import ui, find, tap, tap_node, screenshot

    requested = os.environ.get('SWEEFL_TRACK_CASES', 'hls,dash,single').split(',')
    cases = [value.strip() for value in requested]
    if not cases or any(value not in ('hls', 'dash', 'single') for value in cases) or len(set(cases)) != len(cases):
        raise SystemExit('SWEEFL_TRACK_CASES must list distinct cases from hls,dash,single')
    verify_device()
    results = []
    events = []
    case_floor = 0
    current_title = ''
    current_phase = 'setup'
    result_path = OUT / 'network-tracks.json'

    def event(name, **detail):
        events.append({'event': name, **detail})
        (OUT / 'network-tracks-actions.json').write_text(
            json.dumps(events, ensure_ascii=False, indent=2), encoding='utf-8')

    def device_marker():
        # Same boot-time clock as Android elapsedRealtimeMs; never use a prior app snapshot as a launch fence.
        return int(float(adb('shell', 'cat', '/proc/uptime').split()[0]) * 1000)

    event('configuration', cases=cases)

    def save() -> None:
        result_path.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')

    def record(name: str, passed: bool, detail=None) -> None:
        results.append({'name': name, 'passed': bool(passed), 'detail': detail})
        save()
        if not passed:
            raise AssertionError(f'{name}: {detail}')
        print(name, 'PASS', flush=True)

    def fresh(predicate, after=0, timeout=30):
        after = max(after, case_floor)
        deadline = time.monotonic() + timeout
        last = None
        while time.monotonic() < deadline:
            try:
                last = snapshot()
            except (RuntimeError, ValueError):
                time.sleep(.25)
                continue
            if last.get('elapsedRealtimeMs', 0) > after and predicate(last):
                return last
            time.sleep(.25)
        raise AssertionError({'reason': 'fresh diagnostic deadline', 'afterDeviceUptimeMs': after,
                              'phase': current_phase, 'last': last})

    def bounds(node):
        return tuple(map(int, re.findall(r'\d+', node.get('bounds', ''))))

    def wait_ui(tag, predicate, attempts=5):
        last = None
        for attempt in range(attempts):
            last = ui(f'{tag}-{attempt}')
            if predicate(last):
                return last
            time.sleep(.2)
        raise AssertionError(f'UI condition missing: {tag}')

    def control(label, tag):
        # Scroll only the lower control row, never the video gesture layer.
        tree = ui(tag + '-bar')
        for step in range(4):
            if find(tree, label) is not None:
                return tree
            anchors = [n for n in tree.iter('node') if n.get('text') in ('清晰度', '音轨', '选集', '1.0×') or
                       n.get('content-desc') in ('字幕与音轨', '手势与进度预览', '切换横竖屏')]
            if not anchors:
                break
            boxes = [bounds(n) for n in anchors]
            boxes = [b for b in boxes if len(b) == 4 and b[2] > b[0]]
            if not boxes:
                break
            bottom = max(boxes, key=lambda b: b[3])
            width = max(bounds(n)[2] for n in tree.iter('node') if len(bounds(n)) == 4)
            y = (bottom[1] + bottom[3]) // 2
            start, end = (int(width * .8), int(width * .2)) if step < 2 else (int(width * .2), int(width * .8))
            adb('shell', 'input', 'swipe', str(start), str(y), str(end), str(y), '350')
            tree = ui(tag + '-scroll-' + str(step))
        raise AssertionError('Missing real control-bar selector: ' + label)

    def clickable_target(tree, label):
        node = find(tree, label)
        parents = {child: parent for parent in tree.iter() for child in parent}
        while node is not None:
            box = bounds(node)
            if node.get('enabled') == 'true' and node.get('clickable') == 'true' and len(box) == 4 and box[2] > box[0] and box[3] > box[1]:
                return node
            node = parents.get(node)
        return None

    def pause(tag, initial=False):
        nonlocal current_phase
        current_phase = tag
        state = fresh(lambda s: s.get('playbackState') == 3 and s.get('error') is None,
                      after=device_marker())
        if state['isPlaying']:
            previous_bounds = None
            tree = None
            # Initial Activity layout/optical readiness may still settle. Observe at most three
            # hierarchies and require two matching enabled clickable targets before ONE click.
            for attempt in range(3 if initial else 1):
                tree = wait_ui(tag + '-pause-button-' + str(attempt), lambda t:
                               find(t, current_title) is not None and clickable_target(t, '暂停') is not None)
                target = clickable_target(tree, '暂停')
                target_bounds = bounds(target)
                event('pause target readiness', phase=tag, attempt=attempt,
                      reason='initial layout stability' if initial else 'current enabled target',
                      title=current_title, bounds=target_bounds, snapshot=state)
                if not initial or previous_bounds == target_bounds:
                    break
                previous_bounds = target_bounds
            else:
                raise AssertionError('Initial pause target never stabilized; no click sent')
            event('pause single click', phase=tag, beforeDeviceUptimeMs=device_marker(), bounds=bounds(target))
            tap_node(target)
        marker = device_marker()
        event('pause wait', phase=tag, afterDeviceUptimeMs=marker)
        state = fresh(lambda s: not s['isPlaying'] and s.get('playbackState') == 3, after=marker)
        wait_ui(tag + '-paused', lambda t: find(t, current_title) is not None and find(t, '播放') is not None)
        return state

    def option(tree, token):
        return next((n for n in tree.iter('node') if token in n.get('text', '') and
                     n.get('text', '').strip().removeprefix('✓').strip() != ''), None)

    def select(selector, token, tag, paused=True):
        nonlocal current_phase
        current_phase = tag
        before = fresh(lambda s: s.get('playbackState') == 3 and s.get('error') is None,
                       after=device_marker())
        tap(control(selector, tag), selector)
        tree = wait_ui(tag + '-choices', lambda t: option(t, token) is not None)
        tap_node(option(tree, token))
        marker = device_marker()
        event('track selection', phase=tag, selector=selector, token=token, afterDeviceUptimeMs=marker)
        wait_ui(tag + '-dismissed', lambda t: find(t, '播放信息与解码') is not None)
        after = fresh(lambda s: s.get('error') is None and s.get('playbackState') == 3 and
                      s['isPlaying'] == (not paused), after=marker)
        delta = after['positionMs'] - before['positionMs']
        elapsed = after['elapsedRealtimeMs'] - before['elapsedRealtimeMs']
        record(tag + ' preserves intent and position',
               abs(delta) <= 1500 if paused else -1500 <= delta <= elapsed + 3000,
               {'before': before, 'after': after})
        tap(control(selector, tag + '-verify'), selector)
        selected = wait_ui(tag + '-checked', lambda t: any(
            token in n.get('text', '') and '✓' in n.get('text', '') for n in t.iter('node')))
        record(tag + ' checked choice', True, [n.get('text') for n in selected.iter('node') if '✓' in n.get('text', '')])
        tap(selected, '完成')
        wait_ui(tag + '-return', lambda t: find(t, '播放信息与解码') is not None)
        return after

    def resume_until(tag, predicate):
        nonlocal current_phase
        current_phase = tag
        tap(wait_ui(tag + '-play', lambda t: find(t, '播放') is not None), '播放')
        marker = device_marker()
        event('resume single click', phase=tag, afterDeviceUptimeMs=marker)
        state = fresh(lambda s: s['isPlaying'] and s.get('error') is None and predicate(s),
                      after=marker, timeout=40)
        record(tag + ' actual renderer format', True, state)
        return state

    def launch_case(kind, entry):
        nonlocal case_floor, current_title, current_phase
        current_phase = kind + '-launch'
        current_title = entry.rsplit('/', 1)[-1]
        launch('synthetic/online-r43/' + entry, key='network-tracks-' + kind,
               extra=('--ez', 'keepControls', 'true', '--ez', 'benchmark', 'false'))
        case_floor = device_marker()
        event('launch fence', case=kind, title=current_title, afterDeviceUptimeMs=case_floor)
        fresh(lambda s: s.get('playbackState') == 3 and s.get('renderedFrames', 0) > 0 and not s.get('error'),
              after=case_floor)
        wait_ui(kind + '-current-title', lambda t: find(t, current_title) is not None)

    try:
        for kind, entry in [('hls', 'hls/master.m3u8'), ('dash', 'dash/manifest.mpd')]:
            if kind not in cases:
                continue
            launch_case(kind, entry)
            pause(kind + '-initial', initial=True)
            record(kind + ' quality on real control bar', find(control('清晰度', kind), '清晰度') is not None)
            record(kind + ' audio on real control bar', find(control('音轨', kind), '音轨') is not None)
            record(kind + ' no episode selector', find(ui(kind + '-no-episodes'), '选集') is None)
            for height in (180, 360):
                tag = f'{kind}-{height}p'
                select('清晰度', str(height) + 'p', tag)
                resume_until(tag, lambda s, h=height: (s.get('videoFormat') or {}).get('height') == h)
                pause(tag)
            select('清晰度', '自动选择', kind + '-automatic')
            resume_until(kind + '-automatic', lambda s: (s.get('videoFormat') or {}).get('height') in (180, 360))
            pause(kind + '-automatic')
            # Audio IDs are existing diagnostic fields; also verify the actual checked UI label.
            audio_ids = []
            for language in ('en', 'zh', 'en'):
                tag = kind + '-audio-' + language + '-' + str(len(audio_ids))
                select('音轨', language, tag)
                prior_id = audio_ids[-1] if audio_ids else None
                state = resume_until(tag, lambda s, previous=prior_id:
                                     (s.get('audioFormat') or {}).get('id') is not None and
                                     (previous is None or (s.get('audioFormat') or {}).get('id') != previous))
                audio_ids.append(state['audioFormat']['id'])
                pause(tag)
            record(kind + ' distinct audio renderers selected',
                   len(set(audio_ids)) == 2 and audio_ids[0] == audio_ids[2], audio_ids)
            # Switching while playing must preserve the user's play intent too.
            resume_until(kind + '-playing-switch', lambda s: True)
            select('清晰度', '180p', kind + '-playing-low', paused=False)
            fresh(lambda s: s['isPlaying'] and (s.get('videoFormat') or {}).get('height') == 180)
            pause(kind + '-final')
            record(kind + ' legacy subtitle entry accessible', find(control('字幕与音轨', kind + '-legacy'), '字幕与音轨') is not None)
            screenshot(kind + '-network-tracks')

        if 'single' in cases:
            launch_case('single', 'single.mp4')
            pause('single', initial=True)
            initial_tree = ui('single-initial-controls')
            tree = control('字幕与音轨', 'single')
            record('single stream hides unavailable selectors',
                   all(find(t, x) is None for t in (initial_tree, tree) for x in ('选集', '清晰度', '音轨')))
            record('single stream retains legacy subtitle entry', find(tree, '字幕与音轨') is not None)
            screenshot('single-stream-controls')
    except Exception as error:
        failure = {'name': 'harness failure', 'passed': False, 'phase': current_phase,
                   'error': str(error), 'evidence': {}}
        results.append(failure)
        save()
        tag = 'failure-' + re.sub(r'[^a-zA-Z0-9_-]', '-', current_phase)
        # Each capture is best-effort and independent; preserve the original assertion if one fails.
        for kind, capture in [('screenshot', lambda: str(screenshot(tag))),
                              ('hierarchy', lambda: (ui(tag), str(OUT / (tag + '.xml')))[1]),
                              ('snapshot', snapshot)]:
            try:
                failure['evidence'][kind] = capture()
            except Exception as capture_error:
                failure['evidence'][kind] = {'captureError': str(capture_error)}
            save()
        raise
    finally:
        save()
    print('Network track selector regressions passed; guard restores history/configuration.', flush=True)


if __name__ == '__main__':
    main()
