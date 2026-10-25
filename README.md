# Sweefl Player 0.3.2

面向 Android ARMv8-A / `arm64-v8a` 的原生视频播放器，采用 Kotlin、Compose 与 AndroidX Media3。包含本地与网络播放、系统软硬解及实验性 Android FFmpeg 解码、外挂字幕时间同步、GPU SDR 增强、亮度/音量手势、画中画，以及 AGSL 液态玻璃折射。

**当前源码对应 0.3.2 r42 本地交付版本。** 已完成本地构建及限定设备回归，不等于全部设备兼容或正式商店发布。最新大弹窗性能结果见 [r42 验证说明](docs/DIALOG-PERFORMANCE-r42.md)，此前连续性修复见 [r41 验证说明](docs/GLASS-CONTINUITY-r41.md)，开发入口见 [开发指南](docs/DEVELOPMENT.md)，另一个 agent 接手请先读 [交接文档](docs/AGENT-HANDOFF.md)。

支持 1–32 路共享 HTTP Range 分块加速和 5–600 秒缓冲目标；需要服务器满足长度、Range 和强 ETag 条件，否则兼容回退。HLS/DASH 仅符合条件的大资源内部加速，并非同时预取 32 个分片。

## 使用

- 首页链条图标打开网络视频：输入 HTTP/HTTPS 直链、HLS 或 DASH 清单和自定义 User-Agent。无扩展名清单可指定 HLS / DASH；UA 和流类型按 URL 记住。
- 首页底部胶囊导航保留视频/文件夹；右上角“打开”进入系统文件选择器，支持搜索、排序和继续播放。权限适配 Android 8–14+ 的全部或部分视频访问。
- 播放区横向滑动暂存进度，松手跳转，取消不提交；左半屏上下调整**当前窗口亮度**，右半屏调整媒体音量。
- 默认双击左/中/右分别为后退 10 秒、播放/暂停、快进 10 秒。首页设置或播放页手势按钮可分别自定义三个区域，并切换进度预览。
- 开启预览后，拖动进度条或横滑会在控件上方显示经实际帧时间校验的目标画面；关闭后不创建预览解码器。
- 播放时长按空白画面临时按设置倍率播放，默认 2×，可选 1.25 / 1.5 / 1.75 / 2 / 2.5 / 3×；松手、取消或离开前台恢复原速。暂停、锁定及已开始的滑动不会触发。
- 支持进度拖动、0.5–3× 常规倍速、适配/裁切、横竖屏、触控锁定、音轨和内嵌字幕。
- 字幕按钮可加载/移除 SRT、VTT、ASS/SSA，通过系统文件选择器获取读取权限。外挂字幕支持持久化提前/延后，范围 ±60 秒，正值延后、负值提前，不改变音视频时钟；复杂 ASS 特效仍不保证支持。
- 信息按钮显示实际视频/音频解码器、格式、呈现帧数和丢帧，可切换“自动（优先硬件）”“系统软件解码”与“FFmpeg（实验性兼容模式）”。切换会重新初始化解码器并尽量保留进度，不承诺无缝。
- “画面与渲染设置”提供原生输出与 GPU 增强（输出 SDR），以及亮度、对比度、饱和度调节；实际能力与 HDR 边界见下文。
- 画中画按钮进入系统 PiP；播放中按 Home 可使用系统自动 PiP。小窗隐藏自定义控制层；完全隐藏/关闭后释放播放器。
- 设置及播放信息页可关闭实时折射，并选择 30 / 60 / 90 fps 或跟随屏幕刷新率（默认 60）；这是采样上限，不是片源帧率承诺。玻璃模糊 0–100% 独立持久化，默认 0%，0 档不模糊。Android 13+ 支持 AGSL，旧系统回退到半透明玻璃。

## 解码与 HDR 边界

“系统软件解码”使用系统提供的 software-only MediaCodec；它与独立的“FFmpeg（实验性兼容模式）”不是同一个模式。系统软件模式不偷偷切回硬件；没有可用解码器或平台运行失败时，可选择“改用自动解码”。

FFmpeg 模式通过 Android Nextlib 接入真正的视频和音频 renderer，而非仅加入 Media3 的音频扩展。当前 Nextlib 视频输出是 8-bit YV12、未显式标注输出色彩空间，因此应用拒绝将声明为 PQ/HLG、BT.2020、带静态 HDR 元数据或 Dolby Vision 的轨道交给此视频路径，保留平台解码路径；不能把它称为通用 HDR 软件引擎或全格式保证。GPU 增强模式使用平台视频解码器进入 SDR 渲染图，仍可使用 FFmpeg 音频。具体依赖、源码及限制见[FFmpeg 源码披露](<docs/FFMPEG-SOURCE-0.3.2.md>)。

GPU 增强在设备支持的 Media3/GLES 路径上将 PQ/HLG 映射至 SDR，再应用画面参数；这不等于已校准的色彩还原或完整 Dolby Vision/DRM 支持。Windows LAV、DirectShow 与 MPC 的 Direct3D 渲染器不能直接加载到 Android。

格式能力依赖容器、编码 profile/level、位深、分辨率、帧率、手机解码器与显示链路。设备声明 HDR/Dolby Vision 支持、视频成功出帧和屏幕正确还原动态元数据是不同层次的证据。实测范围和失败项会记录在验证报告中；不能推断所有设备、全部格式或 DRM 内容均可播放。

## 实时玻璃

参考 [shuding/liquid-glass](https://github.com/shuding/liquid-glass) 的圆角距离场与中心方向位移，完整 MIT 许可随包分发。0.3.1 结合 SwiftUI 指南与 [Apple 材质规范](https://developer.apple.com/design/human-interface-guidelines/materials) 调整层级：每组导航/控制共享一层玻璃，进度条使用细轨道，内部按钮、开关与输入框不重复折射，媒体卡片回归普通内容层。r40 媒体库底栏悬浮于内容上方，复用 Compose GPU 绘制层，真实采样滚动列表，无 PixelCopy 回读或轮询定时器。

播放页保留 Media3 原生 SurfaceView。控件使用最高 2560×1440 的视频采样，映射到完整 SurfaceView 显示区域，保留缩放、裁切和屏幕原点；后台复制、主线程发布，单次在途、不积压旧帧。更新受所选档位、屏幕节奏及输出提交证据约束，避免重复复制同一帧；暂停等待经过短暂显现期后降频，稳定帧不持续重复复制。隐藏、关闭折射、PiP 或停止后停止视频取样，几何或跳转代次改变时拒绝旧结果。

弹窗使用独立的完整背景合成：捕获下层 Window UI，在 SurfaceView 透明区域合成视频，再应用当前弹窗实际变暗遮罩；不把弹窗自身采回背景。背景准备好后才显示窗口，等待期间不暴露隐藏交互；滑动模糊使用只增不减的采样支撑档位，保留有效合成。异常等待后显示明确提示的非玻璃面板。K20 Pro/API35 已验证窗口初现及连续拖动；其他设备仍需单独验证。

r41 跳转期间让视频覆盖和玻璃使用同一固定帧，并按提交回调推进新帧交接，持续显示 loading。实时折射关闭、旧系统或纯音频不等待不存在的视频采样，避免控件不可见但仍可点击。具体状态机、原生 Surface 修复和实测结果见 [r41 说明](docs/GLASS-CONTINUITY-r41.md)。

非零模糊使用连续 GPU Gaussian RenderEffect，最大半径 24dp，并扩展三倍半径的边界输入；边缘反光及前景保持锐利。背景传输与边缘使用独立 shader，不再把整个控件子树重复绘成阴影，也不在透明玻璃上使用平台 elevation 投影。瞬态色块的剩余验收范围见[0.3.2 记录](<docs/GLASS-AND-GESTURES-0.3.2.md>)。

ARGB8888 装饰层不是主视频 renderer，不能拿它或屏幕截图证明 HDR 还原；输出提交与有限重采样也不等于合成器逐帧 PTS 证明。

进度预览是独立静音解码路径，使用原 URI、MIME 和 UA，只显示时间戳校验通过的目标帧。直播、多 period 流、超时或资源不足时降级为时间提示；单码率高分辨率文件仍可能需要完整尺寸解码。当前调度、时间校验及缓存限制见[0.3.2 说明](<docs/GLASS-AND-GESTURES-0.3.2.md>)；历史参考来源与兼容规则见[0.3.0 说明](<docs/LIQUID-GLASS-0.3.0.md>)。

## 构建

要求 JDK 17、SDK Platform 35 / Build Tools 35.0.0。项目使用 AGP 8.9.2、Kotlin 2.1.20、Gradle 8.13、Media3 1.8.0；最低 API 26、目标 API 35。

工作区已配置隔离工具，推荐：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build-local.ps1
```

执行 `verify`：架构边界检查、Debug APK 构建、纯 Kotlin 规则测试、Android 模块单元测试与 lint。脚本仅为当前进程设置 JDK/SDK、缓存、代理与临时目录，不修改全局环境或执行策略。

普通环境也可运行：

```powershell
.\gradlew.bat verify
```

开发包 ID 为 `com.sweefl.player`。本版使用 Debug 签名，应用运行不需要 root；已授权设备的 root 仅用于安装、自动化输入与测试诊断。测试不清空用户应用数据、不修改原始媒体、不关闭系统安全设置。

## 源码与维护

`:app` 承载 Android 平台和功能界面，`:core:domain` 承载无 Android 依赖的播放与输入规则。应用内按 `feature`、`data`、`playback`、`core` 和 `di` 分层；Activity 负责系统边界，播放会话负责引擎生命周期，界面接收状态及操作。

- [架构与扩展指南](docs/ARCHITECTURE.md)：目录、依赖方向、生命周期、扩展位置。
- [开发与验证指南](docs/DEVELOPMENT.md)：构建、测试、代码约定及发布检查。
- [设备测试工具](tools/README.md)：设备参数、版本化证据和回归入口。
- [依赖版本目录](gradle/libs.versions.toml)与[架构检查](gradle/architecture.gradle.kts)：统一版本与持续约束。

0.3.0 在 0.2.1 的分层结构上加入统一玻璃与交互功能；保留原应用 ID、入口、播放历史、UA / 流类型配置、字幕与诊断契约。默认 UA 字符串独立于展示版本，不改写既有默认值。

## 项目关系与后续范围

这是经过技术路线选择建立的原生 Android 重建项目。相邻的 MPC-HC Windows 项目仅作为功能参考；没有复制或编译 MFC / DirectShow / DirectX 核心。上游为 [clsid2/mpc-hc](https://github.com/clsid2/mpc-hc)，许可证 GPL-3.0；未来实际复用代码须评估对应许可义务。

尚未覆盖或不作保证：FFmpeg 全格式/完整 HDR 软件输出、DTS/TrueHD 全面支持、复杂 ASS 特效、DLNA/SMB、播放列表、后台纯音频服务、海量媒体库、DRM、所有设备/16KB 页运行兼容、数小时续航测试、正式发布签名与完整无障碍验收。网络功能会按用户提供的 URL/UA 请求媒体；没有广告或分析服务，播放记录保存在本机。

## 许可与本地交付状态

项目所有者已明确批准 0.3.2 组合应用按 GPLv3 **本地交付**，随附完整对应应用及依赖源码；见[LICENSE](<LICENSE>)、[本地分发说明](<DISTRIBUTION-NOTICE.md>)及[FFmpeg 源码披露](<docs/FFMPEG-SOURCE-0.3.2.md>)。随后所有者指定本 GitHub 仓库上传源码；仓库不包含签名私钥、原始设备证据或用户偏好。第三方原始许可与版权声明保留各自条款；仅提供上游链接或依赖包不等于交齐应用对应源码，也不宣称 Nextlib 发布 AAR 已通过逐字节可复现验证。

r41 APK 与对应源码归档已完成本地交付；本仓库上传源码与文档及构建必需的本地 Nextlib AAR，不自动发布二进制 Release。原始设备证据含私人状态，未上传；历史文档中的本地证据链接可能因此不可用。

本版导航、玻璃刷新/对齐、长按倍速和实测结果见 [0.3.2 记录](docs/GLASS-AND-GESTURES-0.3.2.md)。此前视觉调整见 [0.3.1 设计记录](docs/GLASS-REFINEMENT-0.3.1.md)。

交互功能与边界见 [0.3.0 说明](docs/LIQUID-GLASS-0.3.0.md)，此前验证见 [0.3.0 验证报告](docs/VALIDATION-0.3.0.md)及 [原始证据](docs/testing-v030/)。历史 [0.2.1 重构记录](docs/REFACTOR-0.2.1.md)、[0.2.0 验证报告](docs/VALIDATION-0.2.md)、[0.1.0 验证](docs/VALIDATION.md)和旧安装包均保留。素材来源及许可见 [研究报告](docs/research/codec-hdr-power-research.md)。
