"""Summarize per-Window gfxinfo runs; does not infer unique video FPS from UI draws."""
import argparse
import json
import re
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('directories', nargs='+', type=Path)
parser.add_argument('--output', type=Path)
args = parser.parse_args()
results = []
for directory in args.directories:
    files = sorted(directory.rglob('dialog-gfxinfo.txt'))
    if not files:
        raise SystemExit(f'No dialog frame stats in {directory}')
    for path in files:
        text = path.read_text(encoding='utf-8')
        uptime_ns = int(re.search(r'Uptime: (\d+)', text)[1]) * 1_000_000
        windows = []
        for index, block in enumerate(text.split('\nWindow: ')[1:]):
            since = int(re.search(r'Stats since: (\d+)ns', block)[1])
            seconds = (uptime_ns - since) / 1e9
            frames = int(re.search(r'Total frames rendered: (\d+)', block)[1])
            jank = re.search(r'Janky frames: (\d+) \(([\d.]+)%\)', block)
            assert seconds > 0
            windows.append(dict(index=index, seconds=round(seconds, 3), frames=frames,
                drawsPerSecond=round(frames / seconds, 2), jankyFrames=int(jank[1]),
                jankPercent=float(jank[2]),
                p95Ms=int(re.search(r'95th percentile: (\d+)ms', block)[1]),
                gpuP95Ms=int(re.search(r'95th gpu percentile: (\d+)ms', block)[1])))
        assert len(windows) == 2, f'Expected player and open dialog windows: {path}'
        results.append(dict(source=str(path), windows=windows))
report = dict(note='Per-window UI draws, not a count of unique decoded/video backdrop frames. '
    'No screen recording during measurements; compare the same device, build type, '
    'orientation, fixture and blur. Window index follows gfxinfo order.', runs=results)
encoded = json.dumps(report, ensure_ascii=False, indent=2)
if args.output:
    args.output.write_text(encoded + '\n', encoding='utf-8')
print(encoded)
