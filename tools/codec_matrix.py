"""Controlled device codec matrix. Uses local-only HTTP fixtures over adb reverse."""
import argparse, json, time
# Re-exported for compatibility with existing lab scripts.
from device_lab import ADB, SERIAL, ROOT, OUT, adb, snapshot, launch

CASES=[
 ('AVC MP4 AAC','synthetic/avc1080.mp4','AUTO'),
 ('HEVC Main MP4','synthetic/hevc1080.mp4','AUTO'),
 ('AVC MKV AAC','synthetic/avc-aac.mkv','AUTO'),
 ('AVC MPEGTS AAC','synthetic/avc-aac.ts','AUTO'),
 ('AVC MKV SRT','synthetic/avc-aac-srt.mkv','AUTO'),
 ('HEVC Main10 HDR10','media3/mp4__hdr10-720p.mp4','AUTO'),
 ('HEVC HLG','media3/mp4__hlg-1080p.mp4','AUTO'),
 ('AV1 SDR','media3/mp4__sample_av1.mp4','AUTO'),
 ('AV1 HDR10','media3/mp4__hdr10-av1.mp4','AUTO'),
 ('VP9 Profile0','media3/vp9__bear-vp9.webm','AUTO'),
 ('VP9 Profile2 10bit','media3/vp9__roadtrip-vp92-10bit.webm','AUTO'),
 ('Dolby Vision P5','jellyfin/Test Jellyfin 1080p DV P5.mp4','AUTO'),
 ('Dolby Vision P8.1','jellyfin/jellyfin-p8.1-retry.mp4','AUTO'),
 ('Dolby Vision P8.4','shaka/dv-p8-4-hevc__media-video-hvc1-dvh1-db4h-1.mp4','AUTO'),
 ('AVC software','synthetic/avc1080.mp4','SOFTWARE'),
 ('HEVC software','synthetic/hevc1080.mp4','SOFTWARE'),
 ('HEVC Main10 software','media3/mp4__hdr10-720p.mp4','SOFTWARE'),
 ('AV1 software','media3/mp4__sample_av1.mp4','SOFTWARE'),
 ('VP9 software','media3/vp9__bear-vp9.webm','SOFTWARE'),
 ('HLS custom UA','synthetic/hls/index.m3u8','AUTO'),
 ('DASH custom UA','synthetic/dash/index.mpd','AUTO'),
 *[(f'Audio {ext}',f'synthetic/audio.{ext}','AUTO') for ext in ['mp3','flac','ogg','ac3','eac3','opus','wav']],
 ('Wrong UA rejected','synthetic/avc1080.mp4','AUTO'),
]

if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('--only',default=''); options=parser.parse_args()
    results=[]
    for index,(name,path,mode) in enumerate(CASES):
        if options.only and options.only.lower() not in name.lower(): continue
        case={'name':name,'fixture':path,'mode':mode}
        try:
            old=snapshot().get('elapsedRealtimeMs',0)
        except Exception: old=0
        try:
            case['url']=launch(path,mode,'Wrong-UA' if name=='Wrong UA rejected' else 'Sweefl-QA/0.2',key=str(index))
            time.sleep(5)
            sample=snapshot()
            if sample.get('elapsedRealtimeMs',0)<=old: raise RuntimeError('No fresh diagnostics snapshot')
            is_audio=name.startswith('Audio ')
            ok=sample.get('error') is None and sample.get('playbackState') in (3,4) and (sample.get('renderedFrames',0)>0 or (is_audio and sample.get('audioFormat')))
            if name=='Wrong UA rejected': ok=sample.get('error')=='ERROR_CODE_IO_BAD_HTTP_STATUS'
            case.update(passed=bool(ok),snapshot=sample)
            if 'HDR' in name or 'HLG' in name or 'Vision' in name:
                dump=adb('shell','dumpsys','SurfaceFlinger')
                # Keep color/renderer evidence, not unrelated application window names.
                lines=dump.splitlines()
                snippets=[line for line in lines if any(key in line for key in ('BT2020','BT2100','DISPLAY_P3','hdrMetadata','HdrMetadata','activeColorMode','activeRenderIntent','colorMode=','dataspace='))]
                (OUT/f'color-evidence-{index}.txt').write_text('\n'.join(snippets),encoding='utf-8')
        except Exception as e: case.update(passed=False,harnessError=str(e))
        results.append(case)
        suffix='-'+options.only.replace(' ','-') if options.only else ''
        (OUT/f'format-matrix{suffix}.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
        print(name,case['passed'],case.get('snapshot',{}).get('videoDecoder'),case.get('snapshot',{}).get('error'),flush=True)
    adb('shell','am','force-stop','com.sweefl.player')
