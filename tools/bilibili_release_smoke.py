"""Minimal guarded Release Bilibili smoke: real video pixels, UI pause, capability controls.

Lead registers ('bilibili_release_smoke.py', 'bilibili-release') with the existing guard.
No diagnostics.json reads, debug extras, keepControls, account pages, or library inspection.
Importing this module does not touch a device. Requires Pillow and numpy like existing visual tools.
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

import numpy as np
from PIL import Image
from bilibili_playback_check import bounds, find, root_touch, scrub


def video_pixels(path):
    """Central landscape-video region in a portrait screen; exclude center spinner/play glyph.

    Fixed ROI is valid only for this suite's locked portrait layout. No clock, time bar,
    title, status bar, or center loading indicator contributes to motion measurements.
    """
    image = np.asarray(Image.open(path).convert('RGB'), dtype=np.float32)
    h, w = image.shape[:2]
    if h <= w:
        raise AssertionError('Expected portrait screenshot for the defined video ROI')
    x1, x2, y1, y2 = round(w*.20), round(w*.80), round(h*.43), round(h*.57)
    region = image[y1:y2, x1:x2]
    mask = np.ones(region.shape[:2], dtype=bool)
    mask[:, round(w*.38)-x1:round(w*.62)-x1] = False
    pixels = region[mask]
    luma = pixels.mean(axis=1)
    stats = {'roiBounds': [x1, y1, x2, y2], 'excludedCenterX': [round(w*.38), round(w*.62)],
             'meanLuma': float(luma.mean()), 'lumaStd': float(luma.std()),
             'nonblackFraction': float((luma > 20).mean()), 'samplePixels': int(len(pixels))}
    stats['nonblack'] = stats['meanLuma'] > 12 and stats['lumaStd'] > 5 and stats['nonblackFraction'] >= .15
    return pixels, stats


def main():
    backup = os.environ.get('SWEEFL_PREFERENCES_BACKUP')
    if not os.environ.get('SWEEFL_SERIAL') or not backup or not Path(backup).is_file():
        raise SystemExit('Run under guarded_v032.py with explicit SWEEFL_SERIAL and its preferences backup')
    from device_lab import ADB, SERIAL, OUT, adb, verify_device

    verify_device()
    output = OUT / ('release-smoke-' + time.strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:6])
    output.mkdir(parents=True, exist_ok=False)
    results = []
    phase = 'setup'
    safe_labels = {'播放', '暂停', '选集', '音轨', '清晰度', '返回直播', '播放信息与解码',
                   '播放遇到问题', '来源暂时不可用', '返回媒体库', '字幕与音轨', '手势与进度预览',
                   '正在加载，暂停', '正在加载，继续播放', '关闭提示', '重试'}

    def save():
        (output / 'results.json').write_text(json.dumps(scrub(results), ensure_ascii=False, indent=2), encoding='utf-8')

    def record(name, passed, detail=None):
        results.append({'name': name, 'passed': bool(passed), 'detail': scrub(detail)})
        save()
        if not passed:
            raise AssertionError(name)
        print(name, 'PASS', flush=True)

    def root_command(*args):
        response = adb('shell', shlex.join(['su', '-c', shlex.join(args)]))
        if re.search(r'exception|permission\s+(?:denial|denied)|error\s*:', response, re.I):
            raise AssertionError('Root action rejected')
        return response

    def tree(tag):
        remote = '/data/local/tmp/sweefl-release-' + uuid.uuid4().hex + '.xml'
        try:
            response = adb('shell', 'uiautomator', 'dump', remote, timeout=25)
            if 'dumped to:' not in response:
                raise AssertionError('UI dump did not complete')
            raw = ET.fromstring(adb('exec-out', 'cat', remote))
            sanitized = ET.fromstring(ET.tostring(raw))
            for node in sanitized.iter():
                for key in ('text', 'content-desc'):
                    value = node.get(key)
                    if value and value not in safe_labels:
                        node.set(key, '[redacted]')
            ET.ElementTree(sanitized).write(output / (tag + '.xml'), encoding='utf-8', xml_declaration=True)
            return raw
        finally:
            assert re.fullmatch(r'/data/local/tmp/sweefl-release-[0-9a-f]{32}\.xml', remote)
            adb('shell', 'rm', '-f', remote)

    def image(tag):
        remote = '/data/local/tmp/sweefl-release-' + uuid.uuid4().hex + '.png'
        path = output / (tag + '.png')
        try:
            adb('shell', 'screencap', '-p', remote)
            adb('pull', remote, str(path))
            return path
        finally:
            assert re.fullmatch(r'/data/local/tmp/sweefl-release-[0-9a-f]{32}\.png', remote)
            adb('shell', 'rm', '-f', remote)

    def check_errors(root):
        for label in ('播放遇到问题', '来源暂时不可用'):
            if find(root, label) is not None:
                raise AssertionError('Visible player error: ' + label)

    def touch(x, y):
        root_touch(ADB, SERIAL, output / 'input-events.jsonl', 'tap', int(x), int(y))

    def tap_node(node):
        x1, y1, x2, y2 = bounds(node)
        touch((x1+x2)//2, (y1+y2)//2)

    def reveal(root):
        x1, y1, x2, y2 = bounds(next(root.iter('node')))
        touch(x1 + (x2-x1)*.12, y1 + (y2-y1)*.40)

    def motion(kind):
        started = time.monotonic()
        deadline = started + 100
        samples = []
        observations = []
        attempt = 0

        def observe(tag):
            root = tree(tag)
            check_errors(root)
            hud_labels = [label for label in ('播放信息与解码', '暂停', '播放', '播放进度',
                          '正在加载，暂停', '正在加载，继续播放') if find(root, label) is not None]
            observations.append({'elapsedSeconds': round(time.monotonic() - started, 3),
                                 'visibleHudLabels': hud_labels,
                                 'action': 'observe only; no toggle input'})
            (output / (kind + '-hud-observations.json')).write_text(
                json.dumps(observations, ensure_ascii=False, indent=2), encoding='utf-8')
            return bool(hud_labels)

        while time.monotonic() < deadline and attempt < 8:
            hud_visible = observe(f'{kind}-motion-ui-{len(observations)}')
            if time.monotonic() >= deadline:
                break
            if hud_visible:
                # PlayerScreen hides after 4500ms of uninterrupted playback. Buffering may
                # restart that timer; wait beyond it, then OBSERVE again instead of toggling.
                if deadline - time.monotonic() <= 5:
                    break
                time.sleep(5)
                continue
            first_path = image(f'{kind}-video-{attempt}-a')
            first, a = video_pixels(first_path)
            if deadline - time.monotonic() <= 2:
                break
            time.sleep(2)
            second_path = image(f'{kind}-video-{attempt}-b')
            second, b = video_pixels(second_path)
            pair_hud_visible = observe(f'{kind}-motion-post-pair-{attempt}')
            attempt += 1
            if time.monotonic() >= deadline:
                break
            if pair_hud_visible:
                # Keep the images for review but never score a potentially HUD-contaminated pair.
                samples.append({'first': first_path.name, 'second': second_path.name,
                                'discarded': 'HUD visible after screenshot pair'})
                (output / (kind + '-motion.json')).write_text(json.dumps(samples, indent=2), encoding='utf-8')
                continue
            if first.shape != second.shape:
                raise AssertionError('Screenshot geometry changed during video comparison')
            difference = np.abs(second-first).mean(axis=1)
            metrics = {'first': first_path.name, 'second': second_path.name,
                       'firstFrame': a, 'secondFrame': b,
                       'hudAbsentBeforeAndAfterPair': True,
                       'meanAbsoluteDifference': float(difference.mean()),
                       'changedPixelFraction': float((difference > 10).mean()),
                       'thresholds': {'meanAbsoluteDifference': 2.0, 'changedPixelFraction': .03}}
            samples.append(metrics)
            (output / (kind + '-motion.json')).write_text(json.dumps(samples, indent=2), encoding='utf-8')
            if a['nonblack'] and b['nonblack'] and metrics['meanAbsoluteDifference'] > 2.0 and metrics['changedPixelFraction'] >= .03:
                record(kind + ' visible nonblack moving video', True, metrics)
                return
        raise AssertionError(kind + ': 100s playback/loading/HUD deadline or eight image-pair limit; no qualifying HUD-free nonblack moving ROI pair (static content is not proof of decoding)')

    def pause(kind):
        for attempt in range(5):
            root = tree(f'{kind}-pause-ready-{attempt}')
            check_errors(root)
            node = find(root, '暂停')
            if node is not None:
                tap_node(node)  # One observed pause click; do not mask a failed click by repeated toggling.
                for observation in range(3):
                    after = tree(f'{kind}-pause-result-{observation}')
                    check_errors(after)
                    if find(after, '播放') is not None:
                        record(kind + ' pause confirmed by play control', True)
                        image(kind + '-paused')
                        return after
                    if find(after, '暂停') is not None:
                        raise AssertionError('Observed pause click left playback active')
                    if observation == 0:
                        reveal(after)  # Release can hide controls during a slow hierarchy capture.
                raise AssertionError('Pause result not visible after bounded control reveal')
            if find(root, '播放') is not None:
                raise AssertionError('Player became paused before requested smoke pause')
            if find(root, '播放信息与解码') is None:
                reveal(root)
            time.sleep(.3)
        raise AssertionError(kind + ': loading/pause-control deadline')

    def require_control(label, kind):
        for attempt in range(4):
            root = tree(f'{kind}-capability-{attempt}')
            check_errors(root)
            if find(root, label) is not None:
                record(kind + ' control bar ' + label, True)
                return
            anchors = [n for n in root.iter('node') if n.get('text') in ('清晰度', '音轨', '选集', '返回直播', '1.0×') or
                       n.get('content-desc') in ('字幕与音轨', '手势与进度预览')]
            if not anchors:
                reveal(root)
                continue
            row = max((bounds(n) for n in anchors), key=lambda b: b[3])
            width = bounds(next(root.iter('node')))[2]
            x1, x2 = (width*.8, width*.2) if attempt < 2 else (width*.2, width*.8)
            root_touch(ADB, SERIAL, output / 'input-events.jsonl', 'swipe', int(x1), (row[1]+row[3])//2,
                       int(x2), (row[1]+row[3])//2, 350)
        raise AssertionError('Missing expected capability: ' + label)

    rotation = root_command('wm', 'user-rotation').strip()
    if not re.fullmatch(r'free|lock [0-3]', rotation):
        raise AssertionError('Unrecognized rotation state')
    try:
        root_command('wm', 'user-rotation', 'lock', '0')
        for kind, page in [('vod', 'https://www.bilibili.com/video/BV17x411w7KC/?p=1'),
                           ('live', 'https://live.bilibili.com/6')]:
            phase = kind
            adb('shell', 'am', 'force-stop', 'com.sweefl.player')
            adb('shell', 'am', 'start', '-W', '-f', '0x10008000', '-n', 'com.sweefl.player/.PlayerActivity',
                '-d', page, '--es', 'title', 'Bilibili-Release-Smoke', '--es', 'decodeMode', 'AUTO')
            motion(kind)
            pause(kind)
            for label in (('选集',) if kind == 'vod' else ('返回直播', '清晰度')):
                require_control(label, kind)
            image(kind + '-capabilities')
    except Exception as failure:
        results.append({'name': 'release smoke failure', 'passed': False, 'phase': phase, 'error': scrub(str(failure))})
        save()
        if phase in ('vod', 'live'):
            try:
                image(phase + '-failure')
                tree(phase + '-failure')
            except Exception as capture_error:
                results.append({'name': 'failure capture', 'error': scrub(str(capture_error))})
        raise
    finally:
        save()
        root_command('wm', 'user-rotation', *rotation.split())
        restored = root_command('wm', 'user-rotation').strip() == rotation
        (output / 'rotation-restoration.json').write_text(json.dumps({'restored': restored}), encoding='utf-8')
        if not restored:
            raise AssertionError('Rotation restoration mismatch')
    print('Bilibili Release pixel/UI smoke passed; no debug diagnostics used.', flush=True)


if __name__ == '__main__':
    main()
