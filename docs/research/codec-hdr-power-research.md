# Android codec, HDR and power research

Scope: read-only phone inspection and host-side fixture preparation. No app installation, launch, screen capture, playback, benchmark, charging change, CPU setting change, or debugfs mounting was performed. Static declarations and host ffprobe results below do not establish phone playback support.

## Device evidence

ADB first returned one authorized device: serial `75HUJNORLVXOD6S8`, product/device `prague`, model `2604FRK1EC`. `su -c id` returned uid/gid 0, KernelSU context. Properties identify **REDMI K90 Max, MediaTek MT6993, Android 16, arm64-v8a**. MT6993 is the observed SoC identifier; a retail Dimensity marketing-name mapping was not independently verified.

- [Identity properties](device-identity.txt)
- [Physical and virtual display declarations](device-display.txt)
- [Vendor Codec2 XML](vendor-media_codecs_c2.xml)
- [Software Codec2 XML](software-media-codecs.xml)
- [Vendor performance declarations](vendor-media_codecs_performance.xml)
- [Available binder services](device-services.txt)

`dumpsys media.codec` reported “Can't find service: media.codec”. The empty saved file is not evidence of no codecs. The device exposes Codec2 component-store services. XML is used as declaration evidence; live `MediaCodecList` profile/level enumeration and decoder selection still require the lead's app-side work.

### Video decoder declarations

| Format | Vendor implementation | Android software implementation | Interpretation |
|---|---|---|---|
| AVC/H.264 | c2.mtk.avc.decoder | c2.android.avc.decoder | Vendor alias OMX.MTK.VIDEO.DECODER.AVC; both declared |
| HEVC/H.265 | c2.mtk.hevc.decoder | c2.android.hevc.decoder | Main/Main10/profile limits must be queried live |
| VP9 | c2.mtk.vp9.decoder | c2.android.vp9.decoder | Profile 0/2 live enumeration and playback pending |
| AV1 | c2.mtk.av1.decoder | c2.android.av1-dav1d.decoder; c2.android.av1.decoder | dav1d is explicitly present in software APEX XML |
| Dolby Vision | c2.dolby.vision.decoder | No Dolby Vision software decoder declaration found | Does not establish profile 5/8 support or correct DV rendering |
| VP8 | No vendor entry in inspected Codec2 XML | c2.android.vp8.decoder | Software declaration |
| MPEG-4 Part 2/H.263 | Not established | c2.android.mpeg4.decoder; c2.android.h263.decoder | Software declaration |
| MPEG-2 | Not established | c2.android.mpeg2.decoder, domain=tv | Domain restriction may exclude normal phone enumeration |
| APV | Not established | c2.android.apv.decoder, enabled=false | Explicitly disabled; do not claim supported |

Vendor AVC/HEVC/VP9/AV1 entries have secure and low-latency variants; Dolby Vision has a secure variant. Secure variants are not relevant to ordinary clear-file tests. Software entries explicitly carry `software-codec`; vendor naming and OMX aliases identify their vendor path but live `isHardwareAccelerated` should be recorded before presenting a runtime hardware badge.

XML upper bounds: AVC 8192x4320, HEVC/DV 8192x4352, VP9/AV1 8192x4320. Vendor performance points include 3840x2160 at 200 for ordinary AVC/HEVC/VP9/AV1 and at 120 for DV. **These are advertised limits, not achieved frame rates.** XML comments and multiple limit dimensions are not substitutes for `areSizeAndRateSupported`, profile/level checks, or sustained playback.

### Audio declarations

Android software XML lists MP3, AMR-NB/WB, AAC, G.711 A-law/mu-law, Vorbis, Opus, PCM/raw, FLAC, GSM (telephony domain). Vendor [Dolby audio XML](vendor-media_codecs_dolby_audio.xml) declares c2.dolby.eac3.decoder and c2.dolby.ac4.decoder as software codecs; MIME children should be considered separately during live enumeration. No TrueHD/DTS support claim is made from these files. Decoding, channel output, passthrough and object-audio rendering are different capabilities.

The official [Media3 FFmpeg module README](https://github.com/androidx/media/blob/release/libraries/decoder_ffmpeg/README.md) says it provides **FfmpegAudioRenderer**. Adding that extension does not add FFmpeg HEVC video decoding. Saved [reference copy](media3-ffmpeg-extension-README.md). Platform `c2.android.hevc.decoder` is a possible software-video path on this phone, subject to live format support and output correctness. A custom FFmpeg video integration is separate engineering; Windows FFmpeg supplied here is only a host fixture tool.

## HDR display

The physical internal panel advertises 1280x2772 with 60, 120, 144 and 165 Hz modes and supported HDR types `[1,2,3,4]`: Dolby Vision, HDR10, HLG, HDR10+. Reported max and max-average luminance are 1000 nits, min 0. These are API/vendor declarations, not a calibrated luminance measurement. Color modes `[0,7,9]` are advertised. At capture, the dump reported no HDR layer present; that snapshot says nothing about subsequent playback.

The already-existing **scrcpy virtual display has no HDR capabilities**. A mirrored SDR screenshot cannot establish physical HDR/Dolby Vision correctness. Check actual selected decoder, source color metadata, output format/dataspace and physical display HDR state during playback; inspect the physical screen for tint/clipping. Even an HDR layer flag alone does not prove correct Dolby Vision metadata handling.

Authoritative constant definitions: [AOSP Display.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/core/java/android/view/Display.java), saved [source](aosp-Display.java).

## Downloaded fixtures and portable tools

Fixtures live outside the project at `D:\Ai_Projects\.android-tools\media-fixtures`. No files were pushed to the phone. The adjacent JSON source manifests record download URL, pinned commit where applicable, byte size, SHA256 and license notes. Each downloaded media file has an adjacent `.ffprobe.json` after probing.

### AndroidX Media3 (22,143,614 bytes total)

Pinned commit: `8c6678b657ede1e7883fc164ef73ed483c7796c3`. Base URL: `https://raw.githubusercontent.com/androidx/media/8c6678b657ede1e7883fc164ef73ed483c7796c3/libraries/test_data/src/test/assets/media/`.

| Upstream relative path | Verified host metadata | Duration |
|---|---|---|
| mp4/h265_bframes.mp4 | HEVC Main, 854x480, 8-bit | 1.000 s |
| mp4/hdr10-720p.mp4 | HEVC Main10, 1280x720, BT.2020/PQ | 4.237 s |
| mp4/hlg-1080p.mp4 | HEVC Main10, 1920x1080, BT.2020/HLG | 5.167 s |
| mp4/hdr10-av1.mp4 | AV1 Main, 720x1280, 10-bit/PQ | 4.238 s |
| mp4/sample_av1.mp4 | AV1 Main, 1080x720, 8-bit | 1.088 s |
| vp9/bear-vp9.webm | VP9 Profile0, 320x240, 8-bit | 2.703 s |
| vp9/roadtrip-vp92-10bit.webm | VP9 Profile2, 426x240, 10-bit; unspecified transfer | 3.002 s |
| mp4/dolbyVision-hdr.MOV | HEVC Main10, DV profile8 / compatibility4 (8.4), RPU present, HLG, rotation -90 | 0.107 s |

Repository license is Apache-2.0; individual media origin/rights were not independently established. Preserve the source and repository license; prefer explicitly licensed Jellyfin or original synthetic fixtures for redistribution. Short Media3 assets are useful for parser/decoder smoke tests, not sustained performance or power. The VP9 10-bit file must not be labeled HDR solely because it is 10-bit.

### Shaka Player

Downloaded two MP4s from the official Google Shaka repository, pinned commit recorded in `shaka-sources.json`: `dv-p8-1-hevc/media-video-hvc1-dvh1-db1p-1.mp4` (9,583,147 bytes) and `dv-p8-4-hevc/media-video-hvc1-dvh1-db4h-1.mp4` (10,344,595 bytes). Host ffprobe confirms 854x480 Main10, 71.708 s, DV profile8 with compatibility1/PQ and compatibility4/HLG respectively, RPU present, no enhancement layer. Official [integration test](https://github.com/google/shaka-player/blob/main/test/player_dolby_vision_integration.js) explicitly tests P8.1/P8.4 fallback to HEVC. Repository Apache-2.0; media-specific provenance/license remains unresolved.

### Jellyfin

Official [Jellyfin test videos](https://repo.jellyfin.org/test-videos/) explicitly credit **Gnattu** and license the media under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). The saved [index](jellyfin-test-videos.html) provides the license statement and download/hash links. **P5 is validated:** 50,039,227 bytes; publisher SHA256 matches; ffprobe confirms HEVC Main10 1920x1080,29.95s, DV profile5/level5, RPU present, compatibility0. **P8.1 is unusable:** the downloaded 47,041,273 bytes do not match the published SHA256, and ffprobe reports `moov atom not found`. It is retained only as a failed-download artifact; manifest marks `checksumVerified=false`. **Recovery succeeded:** `jellyfin/jellyfin-p8.1-retry.mp4` is 48,198,953 bytes and matches publisher SHA256 `266cda3ffbf46bb73daaf38716ed7dfb2b49e689ca5eb18c585426c1a57957d4`. ffprobe confirms Main10 1920x1080,60fps,29.95s, BT.2020/PQ, DV profile8/level5/compatibility1, RPU present. Its complete source record is `jellyfin-retry-source.json`. Use this recovered file or the validated Shaka P8.1 asset. Total media bytes downloaded including the failed copy and retry: **187,350,809**, below400MB. URLs:

- https://repo.jellyfin.org/test-videos/HDR/Dolby%20Vision/Test%20Jellyfin%201080p%20DV%20P5.mp4
- https://repo.jellyfin.org/test-videos/HDR/Dolby%20Vision/Test%20Jellyfin%201080p%20DV%20P8.1.mp4

The same official index offers licensed 1080p HEVC Main/Main10 SDR, HEVC/AV1 HDR10 3Mbps (~11MB each), and DV8.4. These are good optional additions; not all were downloaded. The [Kodi sample index](https://kodi.wiki/view/Samples) led to Jellyfin; Kodi's fair-use assertion for other linked clips is not a blanket redistribution license.

### Portable FFmpeg

`D:\Ai_Projects\.android-tools\ffmpeg\ffmpeg-n9.0-latest-win64-gpl-shared-9.0\bin\ffmpeg.exe` and `ffprobe.exe` are working. Runtime version: `n9.0.2-28-g669f5ab083-20261016`. BtbN Windows GPL shared distribution is linked from [FFmpeg's official download page](https://ffmpeg.org/download.html), which states FFmpeg itself provides source code. Archive URL: https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-n9.0-latest-win64-gpl-shared-9.0.zip . Archive size 86,574,478 bytes, SHA256 `4ddce00733fcb3907bf56f36ab884e513edad9804642a7e63f4bb4552147ccec`, matched publisher checksum. The moving `latest` URL is preserved alongside full release metadata and checksum file. No global installation/PATH change. Download budget is under150MB; extracted files additionally consume disk space.

Includes libx264/libx265/libvpx/libaom/SVT-AV1, AAC/Opus/Vorbis/FLAC, libass and zimg. Generate synthetic material on the host with disjoint names; the lead owns the generated matrix. No synthetic encoding or device benchmark was performed by this research task.

## Power measurement feasibility

Evidence: [battery sysfs](device-power-counters.txt), [dumpsys battery](device-battery.txt), [CPU frequency/residency](device-cpufreq.txt), [CPU policies](device-cpu-policy-members.txt), [power interface listing](device-power-paths.txt).

Observed one-shot battery/BMS values: status Charging; capacity53%; `current_now=-337000`, `voltage_now=3873000`, `charge_counter=4164000`, `charge_full=8141000`, `temp=318`. Standard Linux power_supply units are microamps, microvolts, microamp-hours and tenths Celsius respectively: -337mA, 3.873V, 4164mAh,31.8°C. Negative current while Charging illustrates that polarity must be checked on this driver rather than assumed from generic Android API expectations. `current_avg=0` is not useful evidence of zero consumption. Battery and BMS duplicate the same gauge values; do not add them together.

USB is online and its vendor values are `voltage_now=4915`, `current_now=457`, `voltage_max=1`; these are inconsistent with standard micro-unit magnitudes and must not be blindly combined with battery values. Validate vendor units first.

During USB charging, battery current is net energy flowing into/out of the battery, while the charger supplies system load and incurs conversion losses. `V*I` is battery-terminal flow, **not app power or decoder watts**. Charge-counter deltas measure net battery charge, not isolated application energy. Screen luminance/refresh, radio, compositor, audio, storage, background services, temperature and charging controller are confounders. A one-shot value is not a benchmark.

`powerstats` service exists but both shell and root dumps returned empty. `android.hardware.power.stats.IPowerStats/default` was absent. `/sys/kernel/debug` listed empty; nothing was mounted. Therefore no per-rail energy counters were established. AIDL absence does not rule out legacy/vendor internals, but root alone does not guarantee calibrated rail telemetry. Official [AOSP PowerStatsService](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/services/core/java/com/android/server/powerstats/PowerStatsService.java) describes energy-meter channels, energy consumers and residency; saved [source](aosp-PowerStatsService.java). If a future permitted Perfetto/PowerStats probe exposes cumulative rail microjoules, compute deltas/time and document rail overlap, sampling granularity and attribution limits. Do not infer decoder watts from CPU frequency.

CPU cpufreq policies0,4,7 are readable, with max2.7,3.5,4.21GHz and `stats/time_in_state`. They can reveal frequency residency and throttling trends. They do not measure power. Official [Linux power_supply documentation](https://github.com/torvalds/linux/blob/master/Documentation/power/power_supply_class.rst), saved [copy](linux-power-supply-class.rst), defines the measurement units and distinction between charge and energy.

For future authorized tests: separately report USB-connected operational observations and controlled battery-discharge/device-power experiments. Use equal brightness/refresh, volume, thermal start state, local files, warm-up and matched durations; repeat in alternating order. Record actual decoder, decoded/rendered/dropped frames, rebuffering, startup/seek latency, CPU time/RSS, temperatures/frequency residency, battery current/voltage/charge counters and uncertainty. Use a calibrated external meter for whole-device input power when available; it still includes charging/system losses. This task did not change any conditions or run those experiments.

## Fixture and playback coverage matrix to execute later

| Axis | Required cases | Evidence to keep |
|---|---|---|
| Video baseline | AVC Baseline/Main/High; HEVC Main/Main10; VP9 p0/p2; AV1 8/10-bit | ffprobe profile, bit depth, decoder selected, completion/errors |
| HDR | HEVC PQ+mastering metadata, HLG, HDR10+, DV5, DV8.1, DV8.4 | Source metadata, DV config, output dataspace/HDR state, physical colors |
| Software | Force explicit c2.android AVC/HEVC/VP9/AV1 where reported supported | Requested versus actual decoder; unsupported must be explicit, never silent fallback labeled software |
| Containers | MP4/MOV, MKV, WebM, TS; fragmented MP4 | Same essence remuxed where valid; seek and duration correctness |
| Resolution/rate | 480p/720p/1080p/4K;24/30/60fps; representative low/high bitrate | Advertised limits separate from observed sustained results |
| Audio | AAC, MP3, FLAC, Opus, Vorbis, PCM, AC3/EAC3; stereo and5.1 | Track selection, audible channels, sync, software/hardware path |
| Subtitles | UTF-8 SRT, WebVTT, ASS, TTML; embedded/external; CJK/RTL/multiline | Rendering, timing, style limits, seek, selection |
| Playback actions | start/end, pause/resume, repeated seek, rotation, background return | No crash/leak, resume and sync; lead owns UI interaction |
| Negative/edge | unsupported profile/chroma/12bit, damaged/truncated input, no audio | Clear error/fallback, stable UI; do not claim all formats |

Do not create “HDR” merely by attaching PQ/BT.2020 flags to SDR pixels. Use genuine downloaded HDR material or a documented color-managed synthetic transform. Do not synthesize a Dolby Vision label without actual valid RPU/configuration. DV5 is not an ordinary HDR10-compatible base layer; DV8.1 may fall back to HDR10 and DV8.4 to HLG while losing Dolby processing. Playback success and Dolby Vision fidelity must be recorded separately.

Research limitations: web_search unavailable because its provider key was not configured; several developer.android.com/source.android.com/Dolby web fetches failed. Official GitHub source mirrors, ffmpeg.org, Kodi and Jellyfin were reachable. No claim of exact decoder power, full profile support, HDR accuracy, or sustained throughput is made.
