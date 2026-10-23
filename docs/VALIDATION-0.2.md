# Sweefl Player 0.2.0 验证报告

日期：2026-10-17。设备：REDMI K90 Max / 2604FRK1EC（prague），MediaTek MT6993，Android 16 / API 36，ARM64，4096 字节内存页。结果限定于本机、这些素材与测试窗口。

## 交付与构建

- APK：[Sweefl Player 0.2.0 ARM64 Debug](../dist/Sweefl-Player-0.2.0-arm64-debug.apk)，23,016,672 字节。
- SHA-256：`CC5478D2362DD2179A4ABDFD20E37F973F21FBC77E87D999C0833C1874F85EFF`。
- 应用 ID `com.sweefl.player`，versionCode 2，最低/目标 API 26/35；Kotlin + Compose + Media3 **1.8.0**。
- `assembleDebug`、9 项单元测试和 `lintDebug` 通过：0 项测试失败，0 条 lint 错误、19 条警告。
- Debug 签名；应用运行不需要 root。测试使用用户已授权的 shell root 安装、自动化输入和读取本应用诊断，保留应用数据与原始媒体。

## 功能验证

已实现 HTTP/HTTPS URL、按 URL 记住的 UA/流类型、HLS/DASH、系统软件解码选择、外置字幕、亮度/音量手势、PiP、实际解码信息和可关闭的实时折射。

| 检查 | 结果 |
| --- | --- |
| URL and custom UA entered through UI; video plays and pauses | 通过 |
| extensionless HLS | 通过 |
| extensionless HLS persisted type and UA | 通过 |
| extensionless DASH | 通过 |
| extensionless DASH persisted type and UA | 通过 |
| HTTP redirect keeps UA | 通过 |
| HTTPS HEVC public fixture | 通过 |
| right vertical gesture changes media volume | 通过 |
| left vertical gesture changes window brightness | 通过 |
| lock exposes only unlock control | 通过 |
| unlock restores controls | 通过 |
| decoder switch preserves near-end paused position | 通过 |
| PiP enters pinned mode and keeps rendering | 通过 |
| PiP hides custom playback controls | 通过 |
| landscape glass playback keeps rendering | 通过 |
| software failure recovers through automatic decode button | 通过 |
| chosen external subtitle overrides embedded default subtitle | 通过 |
| removing external subtitle removes its track | 通过 |
| video reaches playing state | 通过 |
| pause changes control to play | 通过 |
| speed selection | 通过 |
| landscape rotation | 通过 |
| portrait rotation | 通过 |
| playback bookmark persisted | 通过 |
| reopen resumes past bookmark | 通过 |
| 外置 SRT：系统文件选择、明确选中、实际 cue 输出 | 通过，cue=1 |
| 外置 VTT：系统文件选择、明确选中、实际 cue 输出 | 通过，cue=1 |
| 外置 ASS：系统文件选择、明确选中、实际 cue 输出 | 通过，cue=1 |

字幕截图另行人工检查，cue 计数不代替视觉验证。ASS 测试为基本文本/样式，不代表所有动画/卡拉 OK 特效。

修复记录：Media3 1.6.1 首次提取字幕时按开始时间过滤，导致当前仍有效的 SRT/ASS cue 在中途加载时消失；1.8.0 改为按结束时间判断。保留了旧版失败与新版成功的对照 JSON。上游源码：[1.6.1](https://github.com/androidx/media/blob/1.6.1/libraries/extractor/src/main/java/androidx/media3/extractor/text/SubtitleExtractor.java)、[1.8.0](https://github.com/androidx/media/blob/1.8.0/libraries/extractor/src/main/java/androidx/media3/extractor/text/SubtitleExtractor.java)。

## 格式矩阵（有限范围）

每项使用受控 HTTP 素材，启动约 5 秒后读取实际 decoder/格式/呈现帧。短素材循环；音频测试确认格式/播放链路，不代替听感、声道映射或外接设备直通验证。UA 错误项预期服务器拒绝。

| 素材/场景 | 模式 | 实际视频 / 音频解码器 | 尺寸 | 结果 |
| --- | --- | --- | --- | --- |
| AVC MP4 AAC | AUTO | `c2.mtk.avc.decoder / c2.android.aac.decoder` | 1920×1080 | 成功出帧/播放 |
| HEVC Main MP4 | AUTO | `c2.mtk.hevc.decoder / c2.android.aac.decoder` | 1920×1080 | 成功出帧/播放 |
| AVC MKV AAC | AUTO | `c2.mtk.avc.decoder / c2.android.aac.decoder` | 1920×1080 | 成功出帧/播放 |
| AVC MPEGTS AAC | AUTO | `c2.mtk.avc.decoder / c2.android.aac.decoder` | 1920×1080 | 成功出帧/播放 |
| AVC MKV SRT | AUTO | `c2.mtk.avc.decoder / c2.android.aac.decoder` | 1920×1080 | 成功出帧/播放 |
| HEVC Main10 HDR10 | AUTO | `c2.mtk.hevc.decoder / c2.android.aac.decoder` | 1280×720 | 成功出帧/播放 |
| HEVC HLG | AUTO | `c2.mtk.hevc.decoder / c2.android.aac.decoder` | 1920×1080 | 成功出帧/播放 |
| AV1 SDR | AUTO | `c2.mtk.av1.decoder / c2.android.aac.decoder` | 1080×720 | 成功出帧/播放 |
| AV1 HDR10 | AUTO | `c2.mtk.av1.decoder / c2.android.aac.decoder` | 720×1280 | 成功出帧/播放 |
| VP9 Profile0 | AUTO | `c2.mtk.vp9.decoder` | 320×240 | 成功出帧/播放 |
| VP9 Profile2 10bit | AUTO | `c2.mtk.vp9.decoder` | 426×240 | 成功出帧/播放 |
| Dolby Vision P5 | AUTO | `c2.dolby.vision.decoder` | 1920×1080 | 成功出帧/播放 |
| Dolby Vision P8.1 | AUTO | `c2.dolby.vision.decoder` | 1920×1080 | 成功出帧/播放 |
| Dolby Vision P8.4 | AUTO | `c2.dolby.vision.decoder` | 854×480 | 成功出帧/播放 |
| AVC software | SOFTWARE | `c2.android.avc.decoder / c2.android.aac.decoder` | 1920×1080 | 成功出帧/播放 |
| HEVC software | SOFTWARE | `c2.android.hevc.decoder / c2.android.aac.decoder` | 1920×1080 | 成功出帧/播放 |
| HEVC Main10 software | SOFTWARE | `c2.android.hevc.decoder / c2.android.aac.decoder` | — | ERROR_CODE_DECODING_FAILED |
| AV1 software | SOFTWARE | `c2.android.av1-dav1d.decoder / c2.android.aac.decoder` | 1080×720 | 成功出帧/播放 |
| VP9 software | SOFTWARE | `c2.android.vp9.decoder` | 320×240 | 成功出帧/播放 |
| HLS custom UA | AUTO | `c2.mtk.avc.decoder / c2.android.aac.decoder` | 1920×1080 | 成功出帧/播放 |
| DASH custom UA | AUTO | `c2.mtk.avc.decoder / c2.android.aac.decoder` | 1920×1080 | 成功出帧/播放 |
| Audio mp3 | AUTO | `c2.android.mp3.decoder` | — | 成功出帧/播放 |
| Audio flac | AUTO | `c2.android.flac.decoder` | — | 成功出帧/播放 |
| Audio ogg | AUTO | `c2.android.vorbis.decoder` | — | 成功出帧/播放 |
| Audio ac3 | AUTO | `c2.dolby.eac3.decoder.ac3` | — | 成功出帧/播放 |
| Audio eac3 | AUTO | `c2.dolby.eac3.decoder.eac3` | — | 成功出帧/播放 |
| Audio opus | AUTO | `c2.android.opus.decoder` | — | 成功出帧/播放 |
| Audio wav | AUTO | `—` | — | 成功出帧/播放 |
| Wrong UA rejected | AUTO | `—` | — | 通过（预期拒绝） |

29 项矩阵结果：27 项成功播放，1 项按预期拒绝错误 UA，1 项软件 HEVC Main10 样本失败。

原始快照见 [格式 JSON](testing-v020/format-matrix.json)。它记录输入色彩信息、输出 MediaFormat、呈现/丢帧及错误原因；素材来源、哈希和许可见 [研究说明](research/codec-hdr-power-research.md)。没有把库的许可证自动等同于所有测试媒体的再分发许可，APK 不打包这些公开测试片。

### HDR / Dolby Vision 的证据层次

- 设备声明：屏幕支持 DV/HDR10/HLG/HDR10+；平台提供 HEVC/AV1/VP9/DV 解码器。
- 实际解码：矩阵中的 HDR10、HLG、AV1 HDR 与 DV P5/P8.1/P8.4 记录真实 decoder 和出帧。
- 合成链路：SurfaceFlinger 的取样记录包含 BT2020 PQ/HLG dataspace 与 HDR metadata。原始记录为同目录的 color-evidence 文本。
- 未验证：没有色度计/亮度计和参考显示器，不能证明亮度、色彩、DV 动态元数据逐帧还原正确；HDR10+ 实际影片、DV P7/FEL、DRM、高码率 4K 全组合未测。scrcpy 虚拟显示不支持 HDR，截图不能当作 HDR 证明。

### 软件解码边界

软件模式仅使用设备软件 MediaCodec，不是 FFmpeg 视频引擎。初始化回退仍限定在软件列表。本次 HEVC Main10 HDR10 样本（hvc1.2.4.L153，1280×720）在 c2.android.hevc.decoder 返回 NO_EXCEEDS_CAPABILITIES / CodecException 0xe；这不等于所有 Main10 软件视频均失败。失败不静默改硬解；“改用自动解码”的恢复路径已真机通过。

## 性能、温度与功耗边界

同一原创 1080p30 AVC/HEVC 素材，Debug 构建、横屏、窗口亮度 0.4、静音输出但保持音频解码、USB 连接。每个场景 6 秒预热、约 24 秒采样，两轮，第二轮倒序。CPU 以 **100%=一个 CPU 核持续占满** 计；共享 media.swcodec 包括软件解码及音频服务开销，并非严格隔离的单个 codec CPU。

| 场景 | 应用 CPU 均值 | 软件 codec 服务 CPU 均值 | PSS 均值 MiB | 两轮丢帧增量 |
| --- | ---: | ---: | ---: | ---: |
| avc-auto-hidden | 36.2% | 12.5% | 252.3 | 0 |
| hevc-auto-hidden | 36.5% | 12.7% | 260.1 | 0 |
| hevc-software-hidden | 31.2% | 96.1% | 249.8 | 0 |
| avc-controls-no-refraction | 41.1% | 10.2% | 243.6 | 0 |
| avc-controls-refraction | 53.8% | 8.7% | 247.8 | 0 |

采样电池温度范围 35.5–36.2 °C；是短窗口结果，不代表数小时温升/续航。完整样本见 [性能 JSON](testing-v020/performance.json)。

优化与决策：默认优先硬解；进度 UI 仅在可见时高频更新；视频玻璃局部取样宽度 ≤480px、最高 8fps，隐藏控件/PiP 后停止；双缓冲并复用失败取样缓冲；保留折射关闭选项。折射额外 CPU 开销只应按可见控制栏场景解释，不外推为整段观影常驻开销。

可读取 current_now、voltage_now、charge_counter 和温度，但 USB 充电期间电池电流是净电池电流，混合充电器、屏幕、系统和其他负载。本机没有确认可用的 per-rail 能量遥测；因此**没有声称测出播放器/解码器瓦数、精确耗电降幅或续航提升**。

## 视觉与限制

[首页玻璃](testing-v020/screenshots/home-v020.png) · [横屏实时折射](testing-v020/screenshots/landscape-refraction.png) · [SRT](testing-v020/screenshots/external-srt.png) · [VTT](testing-v020/screenshots/external-vtt.png) · [ASS](testing-v020/screenshots/external-ass.png)。以上为实际设备截图。

媒体库 AGSL 镜片折射同一解析背景场景；播放控制栏 PixelCopy 取样实际视频像素并做坐标位移/色散。保留原生 SurfaceView 主视频路径，镜片自身为 SDR UI；旧系统回退为半透明材质。

尚未覆盖 Android 8–15/其他厂商/16KB 页、海量媒体库、全部权限撤销组合、电话和耳机完整矩阵、DRM、DTS/TrueHD、所有 ASS 特效、数小时稳定性及正式发布签名。29 项是本次明确的格式样本矩阵，不是“所有格式全部兼容”的声明。
