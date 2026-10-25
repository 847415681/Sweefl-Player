"""Guarded Debug Bilibili playback acceptance; importing this module never touches a device.

Run through guarded_v032.py with SWEEFL_BILI_CASES=vod,live (default).
Use optional vod-tracks for BV1GJ411x7h7 quality/audio switching coverage.
Use optional vod-resume to verify repeat plain-share-link canonical history restoration.
For a focused live rerun use live-quality, live-pause, live-return or live-rewind;
each independently launches live and establishes the six-second midpoint drag.
No frame-accurate wall-clock claim: Bilibili fMP4 lacks PDT; replay tolerance is 15s.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

URL = re.compile(r"https?://[^\s<>\"']+", re.IGNORECASE)
ONLINE_KEYS = (
    'provider', 'kind', 'qualityId', 'episodeCount', 'episodeIndex', 'audioId', 'audioCount',
    'sourceLoading', 'intendedPlaying', 'rangeStartMs', 'rangeEndMs', 'contentPositionMs',
    'seekable', 'shiftSeconds', 'pausedContentMs',
)
SNAPSHOT_KEYS = (
    'schemaVersion', 'elapsedRealtimeMs', 'videoDecoder', 'audioDecoder', 'renderedFrames',
    'droppedFrames', 'buffering', 'bufferedDurationMs', 'positionMs', 'playbackState',
    'isPlaying', 'playbackSpeed', 'error', 'errorDetail',
)


def scrub(value):
    if isinstance(value, dict):
        return {key: scrub(item) for key, item in value.items()
                if not re.search(r'cookie|authorization|url', str(key), re.IGNORECASE)}
    if isinstance(value, (tuple, list)):
        return [scrub(item) for item in value]
    if isinstance(value, str):
        value = URL.sub('[redacted]', value)
        return re.sub(r'(?i)(?:cookie|sessdata|authorization)\s*[:=][^\r\n]*', '[redacted]', value)
    return value


def evidence(state):
    result = {key: state.get(key) for key in SNAPSHOT_KEYS}
    result['online'] = {key: (state.get('online') or {}).get(key) for key in ONLINE_KEYS}
    for kind in ('videoFormat', 'audioFormat'):
        fmt = state.get(kind) or {}
        result[kind] = {key: fmt.get(key) for key in
                       ('sampleMimeType', 'codecs', 'width', 'height', 'channelCount', 'sampleRate', 'bitrate')}
    return scrub(result)


def bounds(node):
    result = tuple(map(int, re.findall(r'-?\d+', node.get('bounds', ''))))
    if len(result) != 4 or result[2] <= result[0] or result[3] <= result[1]:
        raise AssertionError('Control has no usable bounds')
    return result


def find(tree, label):
    return next((node for node in tree.iter('node')
                 if label in (node.get('text'), node.get('content-desc'))), None)


def online(state):
    return state.get('online') or {}


def decoded(state, kind):
    info = online(state)
    return (info.get('provider') == 'bilibili' and info.get('kind') == kind and
            not info.get('sourceLoading') and state.get('playbackState') == 3 and
            not state.get('error') and state.get('renderedFrames', 0) > 0 and
            bool(state.get('videoDecoder')) and bool(state.get('audioDecoder')) and
            str((state.get('videoFormat') or {}).get('sampleMimeType') or '').startswith('video/') and
            str((state.get('audioFormat') or {}).get('sampleMimeType') or '').startswith('audio/'))


def root_touch(adb_path, serial, log_path, action, *values, timeout=30, runner=subprocess.run):
    """Root injection matches ui_lab; Android may report a denial while exiting zero."""
    expected = {'tap': 2, 'swipe': 5}
    if action not in expected or len(values) != expected[action]:
        raise ValueError('Only tap(x,y) and swipe(x1,y1,x2,y2,durationMs) are supported')
    arguments = [str(value) for value in values]
    if any(re.fullmatch(r'\d{1,6}', value) is None for value in arguments):
        raise ValueError('Input coordinates and duration must be bounded nonnegative integers')
    if action == 'swipe' and not 1 <= int(arguments[-1]) <= 120_000:
        raise ValueError('Swipe duration is outside the supported test range')
    command = shlex.join(['su', '-c', shlex.join(['input', action, *arguments])])
    entry = {'action': action, 'arguments': arguments, 'hostMonotonicMs': round(time.monotonic() * 1000)}

    def output_text(value):
        return value.decode('utf-8', errors='replace') if isinstance(value, bytes) else value or ''

    def persist():
        with Path(log_path).open('a', encoding='utf-8') as stream:
            stream.write(json.dumps(scrub(entry), ensure_ascii=False) + '\n')

    try:
        result = runner([str(adb_path), '-s', serial, 'shell', command], capture_output=True,
                        text=True, encoding='utf-8', errors='replace', timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as failure:
        entry.update(returnCode=None, errorType=type(failure).__name__, error=str(failure),
                     stdout=output_text(getattr(failure, 'stdout', None)),
                     stderr=output_text(getattr(failure, 'stderr', None)), failed=True)
        persist()
        raise AssertionError('Root input transport failed; inspect input-events.jsonl') from None
    entry.update(returnCode=result.returncode, stdout=result.stdout or '', stderr=result.stderr or '')
    combined = entry['stdout'] + '\n' + entry['stderr']
    failure_text = re.search(r'exception|permission\s+(?:denial|denied)|security\s+error|'
                             r'not\s+permitted|access\s+denied|not\s+found|'
                             r'error\s*:|failed|usage\s*:', combined, re.IGNORECASE)
    entry['failed'] = result.returncode != 0 or failure_text is not None
    persist()
    if entry['failed']:
        raise AssertionError('Root input command was rejected; inspect input-events.jsonl')


def main():
    backup = os.environ.get('SWEEFL_PREFERENCES_BACKUP')
    if not os.environ.get('SWEEFL_SERIAL') or not backup or not Path(backup).is_file():
        raise SystemExit('Run under guarded_v032.py with explicit SWEEFL_SERIAL and its preferences backup')
    cases = [case.strip() for case in os.environ.get('SWEEFL_BILI_CASES', 'vod,live').split(',') if case.strip()]
    allowed = {'vod', 'vod-tracks', 'vod-resume', 'live', 'live-quality', 'live-pause', 'live-return', 'live-rewind'}
    if not cases or any(case not in allowed for case in cases) or len(set(cases)) != len(cases):
        raise SystemExit('SWEEFL_BILI_CASES must be unique comma-separated vod/vod-tracks/vod-resume/live/live-quality/live-pause/live-return/live-rewind')
    from device_lab import ADB, SERIAL, adb, snapshot, verify_device, OUT

    verify_device()
    run_dir = OUT / ('bilibili-' + time.strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:6])
    run_dir.mkdir(parents=True, exist_ok=False)
    results = []
    last_state = None

    def save():
        (run_dir / 'results.json').write_text(json.dumps(scrub(results), ensure_ascii=False, indent=2), encoding='utf-8')

    def record(name, condition, detail=None):
        results.append({'name': name, 'passed': bool(condition), 'detail': scrub(detail)})
        save()
        if not condition:
            raise AssertionError(name)
        print(name, 'PASS', flush=True)

    def mark():
        # /proc/uptime and Android elapsedRealtime both count elapsed time since boot, including sleep.
        return int(float(adb('shell', 'cat', '/proc/uptime').split()[0]) * 1000)

    def fresh(predicate, after, timeout=75):
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
        raise AssertionError('Fresh diagnostic condition timed out')

    def tree(tag):
        remote = '/data/local/tmp/sweefl-bili-' + uuid.uuid4().hex + '.xml'
        try:
            response = adb('shell', 'uiautomator', 'dump', remote, timeout=40)
            if 'dumped to:' not in response:
                raise AssertionError('UI hierarchy was not freshly dumped')
            root = ET.fromstring(adb('exec-out', 'cat', remote))
            for node in root.iter():
                for key, value in tuple(node.attrib.items()):
                    node.set(key, scrub(value))
                if node.text:
                    node.text = scrub(node.text)
            ET.ElementTree(root).write(run_dir / (tag + '.xml'), encoding='utf-8', xml_declaration=True)
            return root
        finally:
            if not re.fullmatch(r'/data/local/tmp/sweefl-bili-[0-9a-f]{32}\.xml', remote):
                raise AssertionError('Unexpected temporary hierarchy path')
            adb('shell', 'rm', '-f', remote)

    def image(tag):
        remote = '/data/local/tmp/sweefl-bili-' + uuid.uuid4().hex + '.png'
        try:
            adb('shell', 'screencap', '-p', remote)
            adb('pull', remote, str(run_dir / (tag + '.png')))
        finally:
            if not re.fullmatch(r'/data/local/tmp/sweefl-bili-[0-9a-f]{32}\.png', remote):
                raise AssertionError('Unexpected temporary screenshot path')
            adb('shell', 'rm', '-f', remote)

    def capture(tag, state):
        (run_dir / (tag + '.json')).write_text(json.dumps(evidence(state), ensure_ascii=False, indent=2), encoding='utf-8')
        tree(tag)
        image(tag)

    def tap_node(node):
        if node is None:
            raise AssertionError('Expected UI choice missing')
        x1, y1, x2, y2 = bounds(node)
        root_touch(ADB, SERIAL, run_dir / 'input-events.jsonl', 'tap', (x1 + x2) // 2, (y1 + y2) // 2)

    def control(label, tag):
        for attempt in range(5):
            root = tree(f'{tag}-{attempt}')
            node = find(root, label)
            if node is not None and node.get('enabled', 'true') == 'true':
                return node
            # Scroll the lower selector row, never the video/seek gesture surface.
            anchors = [node for node in root.iter('node') if node.get('text') in
                       ('清晰度', '音轨', '选集', '返回直播', '1.0×') or node.get('content-desc') in
                       ('字幕与音轨', '手势与进度预览', '切换横竖屏')]
            all_boxes = [tuple(map(int, re.findall(r'-?\d+', node.get('bounds', ''))))
                         for node in root.iter('node')]
            all_boxes = [box for box in all_boxes if len(box) == 4 and box[2] > box[0] and box[3] > box[1]]
            boxes = [tuple(map(int, re.findall(r'-?\d+', node.get('bounds', '')))) for node in anchors]
            boxes = [box for box in boxes if len(box) == 4 and box[2] > box[0] and box[3] > box[1]]
            if boxes and all_boxes:
                row = max(boxes, key=lambda box: box[3])
                width = max(box[2] for box in all_boxes)
                start, end = (.8, .2) if attempt < 2 else (.2, .8)
                root_touch(ADB, SERIAL, run_dir / 'input-events.jsonl', 'swipe',
                           int(width * start), (row[1] + row[3]) // 2,
                           int(width * end), (row[1] + row[3]) // 2, 350)
            time.sleep(.3)
        raise AssertionError('Missing enabled control: ' + label)

    def click(label, tag):
        node = control(label, tag)
        stamp = mark()
        tap_node(node)
        return stamp

    def progress(state, tag):
        info = online(state)
        stamp = max(mark(), state['elapsedRealtimeMs'])
        later = fresh(lambda item: decoded(item, info['kind']) and item.get('isPlaying') and
                      online(item).get('intendedPlaying') is True and
                      online(item).get('qualityId') == info.get('qualityId') and
                      online(item).get('episodeIndex') == info.get('episodeIndex') and
                      item.get('renderedFrames', 0) > state['renderedFrames'] + 5 and
                      online(item).get('contentPositionMs', 0) > info['contentPositionMs'] + 1000, stamp)
        record(tag + ' decoded frame and content progression', True,
               {'before': evidence(state), 'after': evidence(later)})
        return later

    def launch_case(kind, tag):
        adb('shell', 'am', 'force-stop', 'com.sweefl.player')
        stamp = mark()
        page = 'https://www.bilibili.com/video/BV17x411w7KC/?p=1' if kind == 'vod' else 'https://live.bilibili.com/6'
        adb('shell', 'am', 'start', '-W', '-f', '0x10008000', '-n', 'com.sweefl.player/.PlayerActivity',
            '-d', page, '--es', 'title', 'Bilibili-QA', '--ez', 'diagnostics', 'true',
            '--ez', 'keepControls', 'true', '--ez', 'benchmark', 'false', '--es', 'decodeMode', 'AUTO')
        state = fresh(lambda item: decoded(item, kind) and item.get('isPlaying') and
                      online(item).get('intendedPlaying') is True and
                      (kind != 'vod' or online(item).get('episodeIndex') == 0), stamp)
        state = progress(state, tag + '-launch')
        capture(tag + '-decoded', state)
        return state

    def pause(kind, tag):
        stamp = click('暂停', tag + '-button')
        state = fresh(lambda item: decoded(item, kind) and not item.get('isPlaying') and
                      online(item).get('intendedPlaying') is False, stamp)
        return state

    def drag(fraction, seconds, tag, kind, playing):
        node = control('播放进度', tag + '-slider')
        x1, y1, x2, y2 = bounds(node)
        inset = min(16, (x2 - x1) // 10)
        left, right = x1 + inset, x2 - inset
        y = (y1 + y2) // 2
        before = fresh(lambda item: decoded(item, kind), mark())
        span = online(before)['rangeEndMs'] - online(before)['rangeStartMs']
        # This slider's semantic bounds include one thumb-height of horizontal padding.
        # Starting elsewhere moves the thumb by a relative delta instead of selecting the
        # pointer's absolute track position. Use the observed thumb-center range.
        thumb_inset = y2 - y1
        thumb_left, thumb_right = x1 + thumb_inset, x2 - thumb_inset
        if thumb_right <= thumb_left:
            raise AssertionError('Slider bounds are too narrow for the observed thumb geometry')
        current_fraction = max(0, min(1, (online(before)['contentPositionMs'] -
                                         online(before)['rangeStartMs']) / max(1, span)))
        start_fraction = 1.0 if kind == 'live' and online(before).get('shiftSeconds') == 0 else current_fraction
        start_x = round(thumb_left + (thumb_right - thumb_left) * start_fraction)
        target_x = round(left + (right - left) * fraction)
        stamp = mark()
        geometry = {'semanticBounds': [x1, y1, x2, y2], 'targetInsetPx': inset,
                    'thumbInsetPx': thumb_inset, 'thumbCenterRangePx': [thumb_left, thumb_right],
                    'startThumbFraction': start_fraction, 'diagnosticContentFraction': current_fraction,
                    'targetFraction': fraction,
                    'startPoint': [start_x, y], 'targetPoint': [target_x, y],
                    'durationSeconds': seconds, 'actionAfterElapsedRealtimeMs': stamp}
        if kind == 'live':
            from slider_gesture_driver import build_driver, run_slider
            # Compile before sampling the target clock; cold tool builds must not skew it.
            build_driver(run_dir)
            before = fresh(lambda item: decoded(item, kind), mark())
            span = online(before)['rangeEndMs'] - online(before)['rangeStartMs']
            stamp = mark()
            geometry = {'driver': 'UiAutomation', 'targetFraction': fraction,
                        'durationSeconds': seconds, 'actionAfterElapsedRealtimeMs': stamp}
        (run_dir / (tag + '-drag-request.json')).write_text(
            json.dumps(geometry, ensure_ascii=False, indent=2), encoding='utf-8')
        if kind == 'live':
            measured = run_slider(ADB, SERIAL, run_dir, tag, fraction, round(seconds * 1000))
            geometry['measuredGesture'] = measured
            held = measured['preUp']
            held_target = held['min'] + (held['max'] - held['min']) * fraction
            record(tag + ' measured held midpoint before UP',
                   measured.get('selectionVerified') is True and abs(held['current'] - held_target) <= 15_000,
                   measured)
            stamp = measured['upElapsedRealtimeMs']
        else:
            root_touch(ADB, SERIAL, run_dir / 'input-events.jsonl', 'swipe',
                       start_x, y, target_x, y, round(seconds * 1000), timeout=max(30, seconds + 10))
        frozen_origin = online(before)['rangeStartMs']
        if kind == 'live':
            down_delay = measured['downElapsedRealtimeMs'] - before['elapsedRealtimeMs']
            if down_delay < 0:
                raise AssertionError('Driver DOWN predates the diagnostic baseline')
            # This case has already verified a full one-hour moving room window. Its
            # origin advances with elapsed time until PlayerScreen freezes it at DOWN.
            frozen_origin += down_delay
            geometry['downBaselineDeltaMs'] = down_delay
            geometry['estimatedFrozenOriginMs'] = frozen_origin
        target = frozen_origin + span * fraction
        state = fresh(lambda item: decoded(item, kind) and item.get('isPlaying') == playing and
                      online(item).get('intendedPlaying') is playing and
                      (kind != 'live' or online(item).get('shiftSeconds', 0) > 0) and
                      abs(online(item).get('contentPositionMs', 0) - target) <= 30_000, stamp)
        record(tag + ' real progress drag', abs(online(state)['contentPositionMs'] - target) <= 30_000,
               {'durationSeconds': seconds, 'fraction': fraction, 'targetMs': target, 'geometry': geometry,
                'before': evidence(before), 'after': evidence(state)})
        capture(tag, state)
        return state

    def vod_case():
        state = launch_case('vod', 'vod')
        info = online(state)
        record('vod P1 metadata and duration', info['episodeCount'] == 10 and info['episodeIndex'] == 0 and
               194_000 <= info['rangeEndMs'] - info['rangeStartMs'] <= 204_000 and info['audioCount'] >= 1,
               evidence(state))
        pause('vod', 'vod-before-episode')
        click('选集', 'vod-episodes')
        choice = None
        for attempt in range(4):
            root = tree('vod-episode-choices-' + str(attempt))
            choice = next((node for node in root.iter('node') if 'Imash li surce' in node.get('text', '')), None)
            if choice is not None:
                break
            time.sleep(.4)
        stamp = mark()
        tap_node(choice)
        state = fresh(lambda item: decoded(item, 'vod') and online(item).get('episodeIndex') == 1 and
                      online(item).get('intendedPlaying') is False and not item.get('isPlaying'), stamp)
        record('vod P2 selected and remains paused', online(state)['episodeCount'] == 10 and
               200_000 <= online(state)['rangeEndMs'] - online(state)['rangeStartMs'] <= 210_000, evidence(state))
        capture('vod-p2-paused', state)
        state = drag(.45, .8, 'vod-paused-seek', 'vod', False)
        later = fresh(lambda item: decoded(item, 'vod') and not item.get('isPlaying'), mark() + 2000)
        record('vod paused seek holds position and intent', online(later).get('intendedPlaying') is False and
               abs(online(later)['contentPositionMs'] - online(state)['contentPositionMs']) <= 2000, evidence(later))
        stamp = click('播放', 'vod-resume')
        state = fresh(lambda item: decoded(item, 'vod') and item.get('isPlaying') and
                      online(item).get('episodeIndex') == 1, stamp)
        state = progress(state, 'vod-p2')
        capture('vod-p2-playing', state)

    def vod_resume_case():
        tag = 'vod-resume'
        alias = 'https://www.bilibili.com/video/BV17x411w7KC/'

        def open_alias(suffix):
            stamp = mark()
            adb('shell', 'am', 'start', '-W', '-f', '0x10008000', '-n', 'com.sweefl.player/.PlayerActivity',
                '-d', alias, '--es', 'title', 'Bilibili-Resume-QA', '--ez', 'diagnostics', 'true',
                '--ez', 'keepControls', 'true', '--ez', 'benchmark', 'false', '--es', 'decodeMode', 'AUTO')
            state = fresh(lambda item: decoded(item, 'vod') and item.get('isPlaying') and
                          online(item).get('intendedPlaying') is True and
                          online(item).get('episodeIndex') == 0, stamp)
            record(tag + '-' + suffix + ' ordinary alias resolves P1',
                   online(state).get('episodeCount') == 10 and
                   194_000 <= online(state)['rangeEndMs'] - online(state)['rangeStartMs'] <= 204_000,
                   {'inputHasPageParameter': False, 'sameAliasBothLaunches': True, 'state': evidence(state)})
            return state, stamp

        # Only the initial setup force-stops. The tested exit must execute normal save/stop lifecycle.
        adb('shell', 'am', 'force-stop', 'com.sweefl.player')
        open_alias('first')
        pause('vod', tag + '-pause')
        selected = drag(.4, 1.2, tag + '-paused-seek', 'vod', False)
        saved = fresh(lambda item: decoded(item, 'vod') and not item.get('isPlaying') and
                      online(item).get('intendedPlaying') is False, mark() + 2000)
        target = online(saved)['contentPositionMs']
        record(tag + ' stable paused history target beyond 30s',
               30_000 < target < online(saved)['rangeEndMs'] - 30_000 and
               abs(target - online(selected)['contentPositionMs']) <= 2000,
               {'selected': evidence(selected), 'beforeNormalExit': evidence(saved)})
        capture(tag + '-before-exit', saved)
        click('返回媒体库', tag + '-normal-exit')
        returned = tree(tag + '-returned')
        record(tag + ' normal finish leaves player controls',
               find(returned, '返回媒体库') is None and find(returned, '播放进度') is None)
        # A new explicit launch after finish, with no saved-state/position extras, reads persisted history.
        restored, launch_stamp = open_alias('reopened')
        delta = online(restored)['contentPositionMs'] - target
        elapsed = restored['elapsedRealtimeMs'] - launch_stamp
        record(tag + ' identical alias restores canonical position instead of zero',
               online(restored)['contentPositionMs'] > 30_000 and -2000 <= delta <= elapsed + 2000,
               {'savedPositionMs': target, 'contentDeltaMs': delta, 'launchElapsedMs': elapsed,
                'before': evidence(saved), 'restored': evidence(restored)})
        capture(tag + '-restored', progress(restored, tag + '-restored'))

    def vod_tracks_case():
        tag = 'vod-tracks'
        adb('shell', 'am', 'force-stop', 'com.sweefl.player')
        stamp = mark()
        adb('shell', 'am', 'start', '-W', '-f', '0x10008000', '-n', 'com.sweefl.player/.PlayerActivity',
            '-d', 'https://www.bilibili.com/video/BV1GJ411x7h7/?p=1', '--es', 'title', 'Bilibili-Tracks-QA',
            '--ez', 'diagnostics', 'true', '--ez', 'keepControls', 'true', '--ez', 'benchmark', 'false',
            '--es', 'decodeMode', 'AUTO')
        state = fresh(lambda item: decoded(item, 'vod') and item.get('isPlaying') and
                      online(item).get('intendedPlaying') is True, stamp)
        record(tag + ' one-part 213s fixture with valid quality and three audio choices',
               isinstance(online(state).get('qualityId'), int) and online(state)['qualityId'] > 0 and
               online(state).get('episodeCount') == 1 and online(state).get('episodeIndex') == 0 and
               208_000 <= online(state)['rangeEndMs'] - online(state)['rangeStartMs'] <= 218_000 and
               online(state).get('audioCount', 0) >= 3,
               evidence(state))
        state = progress(state, tag + '-initial')
        pause('vod', tag + '-pause')
        # A substantial nonzero position makes an accidental restart distinguishable from preservation.
        state = drag(.25, 1.2, tag + '-paused-baseline', 'vod', False)
        first_quality = online(state)['qualityId']
        first_audio = online(state)['audioId']
        first_audio_label = None
        first_quality_label = None

        def signature(item, dimension):
            fields = ('sampleMimeType', 'codecs', 'width', 'height') if dimension == 'quality' else (
                'sampleMimeType', 'codecs', 'channelCount', 'sampleRate', 'bitrate')
            fmt = item.get('videoFormat' if dimension == 'quality' else 'audioFormat') or {}
            return tuple(fmt.get(field) for field in fields)

        def switch(dimension, playing, suffix, requested_quality=None, requested_audio_label=None,
                   expected_audio_id=None, requested_quality_label=None):
            nonlocal first_audio_label, first_quality_label
            name = tag + '-' + suffix
            click('清晰度' if dimension == 'quality' else '音轨', name + '-selector')
            root = tree(name + '-choices')
            labels = [node for node in root.iter('node') if node.get('text', '').strip()]
            if dimension == 'quality':
                options = [node for node in labels if re.search(r'\d+[pPkK]|画质\s*\d+', node.get('text', ''))]
                selected = next((node for node in options if '✓' in node.get('text', '')), None)
                if selected is None:
                    raise AssertionError('Actual selected quality label is missing')
                original_label = selected.get('text', '').replace('✓', '').strip()
                if first_quality_label is None:
                    first_quality_label = original_label
                if requested_quality_label is not None:
                    choice = next((node for node in options if
                                   node.get('text', '').replace('✓', '').strip() == requested_quality_label), None)
                else:
                    def resolution(label):
                        match = re.search(r'(\d+)[pP]', label)
                        return int(match.group(1)) if match else None
                    current_height = resolution(original_label)
                    candidates = [node for node in options if '✓' not in node.get('text', '') and
                                  resolution(node.get('text', '')) != current_height]
                    # Prefer offered 360P/480P entries; discover other resolutions if absent.
                    candidates.sort(key=lambda node: (0 if resolution(node.get('text', '')) in (360, 480) else 1,
                                                       resolution(node.get('text', '')) or 10000))
                    choice = candidates[0] if candidates else None
                    height = resolution(choice.get('text', '')) if choice is not None else None
                    requested_quality = {360: 16, 480: 32}.get(height)
            else:
                options = [node for node in labels if re.search(r'AAC|FLAC|杜比|音轨\s+\d', node.get('text', ''))]
                selected = next((node for node in options if '✓' in node.get('text', '')), None)
                if selected is None or len(options) < 3:
                    raise AssertionError('Expected three real audio choices and a selected audio label')
                original_label = selected.get('text', '').replace('✓', '').strip()
                if first_audio_label is None:
                    first_audio_label = original_label
                if requested_audio_label is not None:
                    choice = next((node for node in options if
                                   node.get('text', '').replace('✓', '').strip() == requested_audio_label), None)
                else:
                    # API IDs are discovered from diagnostics, never guessed from a codec label.
                    # Prefer HE-AAC <-> AAC so the actual decoded format can prove a changed asset.
                    def audio_profile(label):
                        return label.split('·')[0].replace('✓', '').strip()
                    candidates = [node for node in options if '✓' not in node.get('text', '') and
                                  node.get('text', '').strip() != original_label]
                    candidates.sort(key=lambda node: audio_profile(node.get('text', '')) == audio_profile(original_label))
                    choice = candidates[0] if candidates else None
            if choice is None or '✓' in choice.get('text', ''):
                raise AssertionError('No distinct requested VOD track choice is available')
            selected_label = choice.get('text', '').strip()
            before = fresh(lambda item: decoded(item, 'vod') and item.get('isPlaying') is playing and
                           online(item).get('intendedPlaying') is playing, mark())
            stamp = mark()
            tap_node(choice)
            after = fresh(lambda item: decoded(item, 'vod') and item.get('isPlaying') is playing and
                          online(item).get('intendedPlaying') is playing and
                          ((online(item).get('qualityId') != online(before).get('qualityId') and
                            (requested_quality is None or online(item).get('qualityId') == requested_quality))
                           if dimension == 'quality' else
                           online(item).get('audioId') != online(before).get('audioId') and
                           (expected_audio_id is None or online(item).get('audioId') == expected_audio_id)), stamp)
            delta = online(after)['contentPositionMs'] - online(before)['contentPositionMs']
            elapsed = after['elapsedRealtimeMs'] - before['elapsedRealtimeMs']
            position_kept = (-2000 <= delta <= elapsed + 2000) if playing else abs(delta) <= 2000
            other_kept = (online(after)['audioId'] == online(before)['audioId'] if dimension == 'quality' else
                          online(after)['qualityId'] == online(before)['qualityId'])
            detail = {'choiceLabel': selected_label, 'requestedQuality': requested_quality,
                      'initialQualityId': first_quality, 'initialQualityLabel': first_quality_label,
                      'initialAudioId': first_audio, 'initialAudioLabel': first_audio_label,
                      'expectedAudioId': expected_audio_id, 'contentDeltaMs': delta, 'elapsedMs': elapsed,
                      'before': evidence(before), 'after': evidence(after)}
            record(name + ' actual id and decoded format changed',
                   signature(before, dimension) != signature(after, dimension), detail)
            if dimension == 'quality':
                record(name + ' decoded resolution changed',
                       tuple((before.get('videoFormat') or {}).get(key) for key in ('width', 'height')) !=
                       tuple((after.get('videoFormat') or {}).get(key) for key in ('width', 'height')), detail)
            record(name + ' preserves content position, intent and other selected track',
                   position_kept and other_kept and online(after)['episodeIndex'] == online(before)['episodeIndex'], detail)
            if playing:
                later = progress(after, name)
                record(name + ' growing frames retain selected audio id',
                       online(later)['audioId'] == online(after)['audioId'], evidence(later))
            else:
                later = fresh(lambda item: decoded(item, 'vod') and not item.get('isPlaying') and
                              online(item).get('intendedPlaying') is False, mark() + 2000)
                record(name + ' remains paused at selected position',
                       abs(online(later)['contentPositionMs'] - online(after)['contentPositionMs']) <= 2000 and
                       online(later)['audioId'] == online(after)['audioId'] and
                       online(later)['qualityId'] == online(after)['qualityId'], evidence(later))
            capture(name, later)
            return later

        switch('quality', False, 'paused-quality')
        switch('audio', False, 'paused-audio')
        stamp = click('播放', tag + '-resume')
        state = fresh(lambda item: decoded(item, 'vod') and item.get('isPlaying') and
                      online(item).get('intendedPlaying') is True, stamp)
        progress(state, tag + '-resumed')
        switch('quality', True, 'playing-quality', requested_quality=first_quality,
               requested_quality_label=first_quality_label)
        switch('audio', True, 'playing-audio', requested_audio_label=first_audio_label, expected_audio_id=first_audio)

    def live_quality(tag):
        click('清晰度', tag + '-selector')
        root = tree(tag + '-choices')
        options = [node for node in root.iter('node') if node.get('text', '').strip() and
                   node.get('text') not in ('清晰度', '完成') and '✓' not in node.get('text', '') and
                   re.search(r'\d+[pPkK]|原画|蓝光|超清|高清|流畅|画质\s*\d+', node.get('text', ''))]
        if not options:
            raise AssertionError('No distinct live quality option is available')
        selected_label = options[-1].get('text')
        before = fresh(lambda item: decoded(item, 'live') and item.get('isPlaying'), mark())
        stamp = mark()
        tap_node(options[-1])
        after = fresh(lambda item: decoded(item, 'live') and item.get('isPlaying') and
                      online(item).get('intendedPlaying') is True and online(item).get('shiftSeconds', 0) > 0 and
                      online(item).get('qualityId') != online(before)['qualityId'], stamp)
        delta = online(after)['contentPositionMs'] - online(before)['contentPositionMs']
        elapsed = after['elapsedRealtimeMs'] - before['elapsedRealtimeMs']
        record(tag + ' actual quality change retains replay content within 15s', abs(delta - elapsed) <= 15_000,
               {'choiceLabel': selected_label, 'contentDeltaMs': delta, 'elapsedMs': elapsed,
                'before': evidence(before), 'after': evidence(after)})
        capture(tag, progress(after, tag))

    def live_pause(tag):
        before = pause('live', tag)
        record(tag + ' has frozen logical target', online(before).get('pausedContentMs') is not None, evidence(before))
        time.sleep(12)
        after = fresh(lambda item: decoded(item, 'live') and not item.get('isPlaying') and
                      online(item).get('intendedPlaying') is False, mark())
        record(tag + ' frozen for at least 12s within 2s',
               after['elapsedRealtimeMs'] - before['elapsedRealtimeMs'] >= 12_000 and
               abs(online(after)['contentPositionMs'] - online(before)['contentPositionMs']) <= 2000,
               {'before': evidence(before), 'after': evidence(after)})
        capture(tag + '-frozen', after)
        stamp = click('播放', tag + '-resume')
        resumed = fresh(lambda item: decoded(item, 'live') and item.get('isPlaying') and
                        online(item).get('intendedPlaying') is True and online(item).get('shiftSeconds', 0) > 0 and
                        online(item).get('pausedContentMs') is None, stamp)
        elapsed = resumed['elapsedRealtimeMs'] - stamp
        delta = online(resumed)['contentPositionMs'] - online(before)['contentPositionMs']
        record(tag + ' resumed frozen content within 15s', abs(delta - elapsed) <= 15_000,
               {'resumeElapsedMs': elapsed, 'contentDeltaMs': delta, 'state': evidence(resumed)})
        capture(tag + '-resumed', progress(resumed, tag + '-resume'))

    def live_return(tag):
        stamp = click('返回直播', tag + '-button')
        state = fresh(lambda item: decoded(item, 'live') and item.get('isPlaying') and
                      online(item).get('intendedPlaying') is True and online(item).get('shiftSeconds') == 0, stamp)
        record(tag + ' actual shift removed', 0 <= online(state)['rangeEndMs'] - online(state)['contentPositionMs'] <= 15_000,
               evidence(state))
        capture(tag, progress(state, tag))

    def live_rewind(tag):
        before = pause('live', tag + '-pause')
        stamp = click('后退 10 秒', tag + '-button')
        state = fresh(lambda item: decoded(item, 'live') and not item.get('isPlaying') and
                      online(item).get('intendedPlaying') is False and online(item).get('shiftSeconds', 0) > 0 and
                      abs(online(item).get('contentPositionMs', 0) - (online(before)['contentPositionMs'] - 10_000)) <= 2000,
                      stamp)
        record(tag + ' ten-second logical rewind retains pause', True,
               {'before': evidence(before), 'after': evidence(state)})
        stamp = click('播放', tag + '-resume')
        state = fresh(lambda item: decoded(item, 'live') and item.get('isPlaying') and
                      online(item).get('intendedPlaying') is True and online(item).get('shiftSeconds', 0) > 0, stamp)
        capture(tag, progress(state, tag))

    try:
        for case in cases:
            if case == 'vod':
                vod_case()
                continue
            if case == 'vod-resume':
                vod_resume_case()
                continue
            if case == 'vod-tracks':
                vod_tracks_case()
                continue
            state = launch_case('live', case)
            info = online(state)
            record(case + ' real one-hour seekable window', info['seekable'] is True and
                   3_590_000 <= info['rangeEndMs'] - info['rangeStartMs'] <= 3_610_000 and
                   info['shiftSeconds'] == 0, evidence(state))
            state = drag(.5, 6, case + '-midpoint', 'live', True)
            record(case + ' delayed stream uses positive shift', 1500 <= online(state)['shiftSeconds'] <= 2100,
                   evidence(state))
            progress(state, case + '-replay')
            if case in ('live', 'live-quality'):
                live_quality(case + '-quality')
            if case in ('live', 'live-pause'):
                live_pause(case + '-pause')
            if case in ('live', 'live-return', 'live-rewind'):
                live_return(case + '-return')
            if case in ('live', 'live-rewind'):
                live_rewind(case + '-rewind')
    except Exception as error:
        results.append({'name': 'harness failure', 'passed': False, 'error': scrub(str(error)),
                        'lastFreshState': evidence(last_state) if last_state else None})
        save()
        try:
            tree('failure')
            image('failure')
        except Exception:
            pass
        raise AssertionError(scrub(str(error))) from None
    finally:
        save()
    print('Bilibili acceptance passed; guard owns restoration. Evidence:', run_dir, flush=True)


if __name__ == '__main__':
    main()
