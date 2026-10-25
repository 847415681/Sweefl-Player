"""Generate original, reproducible 120-second HLS/DASH selector fixtures; no device access.

Default output is a NEW online-r43 directory. Existing directories are never overwritten.
Use --output-dir with a new path to repeat a generation. Requires FFmpeg with libx264/AAC.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT.parent / '.android-tools'


def generate(ffmpeg: Path, output: Path) -> None:
    if not ffmpeg.is_file():
        raise FileNotFoundError(ffmpeg)
    if output.exists():
        raise FileExistsError(f'Refusing to overwrite existing fixture directory: {output}; choose --output-dir')
    output.mkdir(parents=True)
    commands = []
    version = subprocess.run([str(ffmpeg), '-version'], check=True, capture_output=True, text=True).stdout.splitlines()[0]

    def run(*args: str) -> None:
        command = [str(ffmpeg), '-hide_banner', '-loglevel', 'warning', '-nostdin', '-n', *args]
        commands.append(command)
        with (output / 'commands.json').open('w', encoding='utf-8') as handle:
            json.dump(commands, handle, indent=2)
        subprocess.run(command, cwd=output, check=True)

    # Fixed GOP boundaries let representations switch on every four-second segment.
    run('-f', 'lavfi', '-i', 'testsrc2=size=640x360:rate=30',
        '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000',
        '-f', 'lavfi', '-i', 'sine=frequency=880:sample_rate=48000',
        '-filter_complex', '[0:v]split=2[high][lowin];[lowin]scale=320:180[low]',
        '-map', '[high]', '-map', '[low]', '-map', '1:a', '-map', '2:a', '-t', '120',
        '-c:v', 'libx264', '-preset', 'veryfast', '-profile:v', 'high', '-level:v', '3.0',
        '-pix_fmt', 'yuv420p', '-g', '120', '-keyint_min', '120', '-sc_threshold', '0',
        '-threads:v:0', '2', '-threads:v:1', '2',
        '-b:v:0', '900k', '-maxrate:v:0', '1100k', '-bufsize:v:0', '1800k',
        '-b:v:1', '250k', '-maxrate:v:1', '350k', '-bufsize:v:1', '500k',
        '-c:a', 'aac', '-b:a', '96k', '-ac', '1',
        '-metadata:s:a:0', 'language=eng', '-metadata:s:a:0', 'title=English 440 Hz',
        '-metadata:s:a:1', 'language=zho', '-metadata:s:a:1', 'title=Chinese 880 Hz',
        '-movflags', '+faststart', 'source.mp4')
    run('-i', 'source.mp4', '-map', '0:v:0', '-map', '0:a:0', '-c', 'copy', '-movflags', '+faststart', 'single.mp4')

    (output / 'hls').mkdir()
    for name, stream in [('high', '0:v:0'), ('low', '0:v:1'), ('en', '0:a:0'), ('zh', '0:a:1')]:
        run('-i', 'source.mp4', '-map', stream, '-c', 'copy', '-f', 'hls',
            '-hls_time', '4', '-hls_playlist_type', 'vod', '-hls_flags', 'independent_segments',
            '-hls_segment_filename', f'hls/{name}-%03d.ts', f'hls/{name}.m3u8')
    (output / 'hls/master.m3u8').write_text(
        '#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-INDEPENDENT-SEGMENTS\n'
        '#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English 440 Hz",LANGUAGE="en",DEFAULT=YES,AUTOSELECT=YES,URI="en.m3u8"\n'
        '#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Chinese 880 Hz",LANGUAGE="zh",DEFAULT=NO,AUTOSELECT=YES,URI="zh.m3u8"\n'
        '#EXT-X-STREAM-INF:BANDWIDTH=1250000,AVERAGE-BANDWIDTH=1000000,RESOLUTION=640x360,FRAME-RATE=30.000,CODECS="avc1.64001e,mp4a.40.2",AUDIO="audio"\n'
        'high.m3u8\n'
        '#EXT-X-STREAM-INF:BANDWIDTH=450000,AVERAGE-BANDWIDTH=350000,RESOLUTION=320x180,FRAME-RATE=30.000,CODECS="avc1.64001e,mp4a.40.2",AUDIO="audio"\n'
        'low.m3u8\n', encoding='utf-8')

    (output / 'dash').mkdir()
    run('-i', 'source.mp4', '-map', '0:v:0', '-map', '0:v:1', '-map', '0:a:0', '-map', '0:a:1',
        '-c', 'copy', '-metadata:s:a:0', 'language=en', '-metadata:s:a:1', 'language=zh',
        '-f', 'dash', '-seg_duration', '4', '-use_template', '1', '-use_timeline', '1',
        '-adaptation_sets', 'id=0,streams=0,1 id=1,streams=2 id=2,streams=3', 'dash/manifest.mpd')
    ns = {'m': 'urn:mpeg:dash:schema:mpd:2011'}
    manifest = ET.parse(output / 'dash/manifest.mpd')
    video = manifest.findall('.//m:AdaptationSet[@contentType="video"]/m:Representation', ns)
    audio = manifest.findall('.//m:AdaptationSet[@contentType="audio"]', ns)
    assert {(int(v.attrib['width']), int(v.attrib['height'])) for v in video} == {(640, 360), (320, 180)}
    assert {a.attrib.get('lang') for a in audio} == {'en', 'zh'}, [a.attrib for a in audio]
    for name in ('high', 'low', 'en', 'zh'):
        playlist = (output / f'hls/{name}.m3u8').read_text(encoding='utf-8')
        assert '#EXT-X-ENDLIST' in playlist
        assert all((output / 'hls' / line).is_file() for line in playlist.splitlines() if line and not line.startswith('#'))
    for entry in ('source.mp4', 'single.mp4', 'hls/master.m3u8', 'dash/manifest.mpd'):
        run('-i', entry, '-map', '0', '-t', '2', '-f', 'null', '-')
    media_files = sorted(path for path in output.rglob('*') if path.is_file() and path.name != 'commands.json')
    provenance = {
        'source': 'Original lavfi testsrc2 and sine; no user media or external downloads',
        'ffmpeg': version, 'durationSeconds': 120,
        'video': [{'width': 640, 'height': 360, 'bitrate': 900000}, {'width': 320, 'height': 180, 'bitrate': 250000}],
        'audio': [{'language': 'en', 'frequencyHz': 440}, {'language': 'zh', 'frequencyHz': 880}],
        'entrypoints': ['hls/master.m3u8', 'dash/manifest.mpd', 'single.mp4'],
        'sha256': {str(p.relative_to(output)).replace('\\', '/'): hashlib.sha256(p.read_bytes()).hexdigest() for p in media_files},
    }
    (output / 'provenance.json').write_text(json.dumps(provenance, indent=2), encoding='utf-8')
    print(f'Generated and structurally verified {len(media_files)} fixture files in {output}', flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ffmpeg', type=Path, default=TOOLS / 'ffmpeg/ffmpeg-n9.0-latest-win64-gpl-shared-9.0/bin/ffmpeg.exe')
    parser.add_argument('--output-dir', type=Path, default=TOOLS / 'media-fixtures/synthetic/online-r43')
    args = parser.parse_args()
    generate(args.ffmpeg.resolve(), args.output_dir.resolve())


if __name__ == '__main__':
    main()
