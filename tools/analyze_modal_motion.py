"""Estimate visible modal texture changes, independently of gfxinfo draw counts.

Uses native video frames at 780x360 and a fixed landscape modal interior. The
three thresholds expose compression-noise sensitivity; this is not a PTS proof.
"""
import argparse
import json
import subprocess
from pathlib import Path
import numpy as np

parser = argparse.ArgumentParser()
parser.add_argument('directories', nargs='+', type=Path)
parser.add_argument('--ffprobe', required=True)
parser.add_argument('--output', type=Path)
args = parser.parse_args()
results = []
for directory in args.directories:
    for name in ('info-open', 'gestures-open'):
        frames = np.memmap(directory / (name + '.rgb'), dtype=np.uint8, mode='r').reshape(-1,360,780,3)
        probe = json.loads(subprocess.check_output([args.ffprobe, '-v', 'error', '-select_streams', 'v:0',
            '-show_entries', 'frame=best_effort_timestamp_time', '-of', 'json', str(directory / (name + '.mp4'))]))
        times = [float(f['best_effort_timestamp_time']) for f in probe['frames']]
        assert len(frames) == len(times) and times[-1] >= 10, 'Native decoding or coverage mismatch'
        changes = []
        for i in range(1, len(frames)):
            if not 2 < times[i] <= 10:
                continue
            current = frames[i,55:325,145:635].astype(np.int16)
            previous = frames[i-1,55:325,145:635].astype(np.int16)
            changes.append(float(np.abs(current - previous).mean()))
        results.append(dict(source=str(directory / (name + '.mp4')), windowSeconds=8,
            nativeSamples=len(changes),
            changesPerSecond={str(t):round(sum(x > t for x in changes)/8, 2) for t in (.25,.5,1.)},
            meanAbsoluteDifferencePercentiles=[round(float(x),3) for x in np.percentile(changes,[10,25,50,75,90])]))
report = dict(note='Estimated visible texture changes in a fixed modal ROI, seconds 2..10; '
    'not decoder FPS, and bounded by screenrecord. Thresholds are mean absolute RGB differences '
    'on the 0..255 scale. Inspect first frames and geometry separately.', runs=results)
text = json.dumps(report,ensure_ascii=False,indent=2)
if args.output:
    args.output.write_text(text+'\n',encoding='utf-8')
print(text)
