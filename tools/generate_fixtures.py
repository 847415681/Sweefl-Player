"""Create deterministic, original test fixtures; no user media is read."""
from pathlib import Path
import subprocess, json
root = Path(r'D:\Ai_Projects\.android-tools\media-fixtures\synthetic')
root.mkdir(parents=True, exist_ok=True)
ffmpeg = r'D:\Ai_Projects\.android-tools\ffmpeg\ffmpeg-n9.0-latest-win64-gpl-shared-9.0\bin\ffmpeg.exe'

def run(args, cwd=None):
    subprocess.run([ffmpeg, '-hide_banner', '-loglevel', 'error', '-y', *args], check=True, cwd=cwd)

source = ['-f','lavfi','-i','testsrc2=size=1920x1080:rate=30','-f','lavfi','-i','sine=frequency=440:sample_rate=48000','-t','40']
for name, codec, extra in [('avc1080.mp4','libx264',['-preset','fast','-crf','23']), ('hevc1080.mp4','libx265',['-preset','fast','-crf','25','-x265-params','pools=6:log-level=error','-tag:v','hvc1'])]:
    if not (root/name).exists():
        run([*source,'-c:v',codec,*extra,'-pix_fmt','yuv420p','-c:a','aac','-b:a','128k','-movflags','+faststart',str(root/name)])
    print(name, flush=True)
for extension in ['mkv','ts']:
    run(['-i',str(root/'avc1080.mp4'),'-c','copy',str(root/f'avc-aac.{extension}')])
for codec, name in [('libmp3lame','audio.mp3'),('flac','audio.flac'),('libvorbis','audio.ogg'),('ac3','audio.ac3'),('eac3','audio.eac3'),('libopus','audio.opus'),('pcm_s16le','audio.wav')]:
    run(['-f','lavfi','-i','sine=frequency=330:sample_rate=48000','-t','8','-c:a',codec,str(root/name)])
(root/'Sweefl-test.srt').write_text('1\n00:00:00,000 --> 00:00:40,000\nSWEEFL EXTERNAL SUBTITLE TEST\n',encoding='utf-8')
(root/'Sweefl-test.vtt').write_text('WEBVTT\n\n00:00:00.000 --> 00:00:40.000\nSWEEFL VTT SUBTITLE TEST\n',encoding='utf-8')
(root/'Sweefl-test.ass').write_text('[Script Info]\nScriptType: v4.00+\nPlayResX: 1920\nPlayResY: 1080\n[V4+ Styles]\nFormat: Name,Fontname,Fontsize,PrimaryColour,SecondaryColour,OutlineColour,BackColour,Bold,Italic,Underline,StrikeOut,ScaleX,ScaleY,Spacing,Angle,BorderStyle,Outline,Shadow,Alignment,MarginL,MarginR,MarginV,Encoding\nStyle: Default,Arial,48,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,1,2,20,20,50,1\n[Events]\nFormat: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text\nDialogue: 0,0:00:00.00,0:00:40.00,Default,,0,0,0,,SWEEFL ASS SUBTITLE TEST\n', encoding='utf-8')
run(['-i',str(root/'avc1080.mp4'),'-i',str(root/'Sweefl-test.srt'),'-map','0','-map','1','-c','copy','-c:s','srt',str(root/'avc-aac-srt.mkv')])
for kind in ['hls','dash']:
    folder=root/kind; folder.mkdir(exist_ok=True)
    args=['-i',str(root/'avc1080.mp4'),'-c','copy']
    if kind=='hls': args += ['-hls_time','4','-hls_playlist_type','vod',str(folder/'index.m3u8')]
    else: args += ['-f','dash','-seg_duration','4',str(folder/'index.mpd')]
    run(args, cwd=folder)
(root/'provenance.json').write_text(json.dumps({'source':'Original lavfi testsrc2 and sine generated for Sweefl Player tests','durationSeconds':40,'video':'1080p30 AVC/HEVC SDR; all synthetic','audio':'440Hz AAC 128k; additional 330Hz format fixtures','warning':'Not evidence of complex ASS effects, HDR accuracy or high-bitrate 4K support'},indent=2),encoding='utf-8')
print('Synthetic fixtures ready', flush=True)
