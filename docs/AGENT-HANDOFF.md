# Agent 交接：Sweefl Player r43

更新：2026-10-25（r43 交付后整理）。本文件是新 agent 的入口；按顺序阅读本页、[r43 交付快照](RELEASE-r43.md)、[开发指南](DEVELOPMENT.md)、[架构指南](ARCHITECTURE.md)。涉及玻璃或原生代码时，再读 [r42 性能说明](DIALOG-PERFORMANCE-r42.md)和 [r41 连续性说明](GLASS-CONTINUITY-r41.md)。用户已授权将完整修复和开发文档提交到指定 GitHub 仓库。

## 可直接转发给下一位 agent

```text
在 D:\Ai_Projects\sweefl-player 接手 Sweefl Player。
先读项目 AGENTS.md、docs/AGENT-HANDOFF.md、docs/RELEASE-r43.md，
再按任务阅读 docs/DEVELOPMENT.md 和 docs/ARCHITECTURE.md。
当前功能基线为 r43，功能提交 862c8951ed148042884a628e1196e932bc38494b，
对应 CI 已成功，Release 已完成真机验收。后续文档提交不改变该 APK 基线。
先检查 git status、git log -3 和 git remote -v，保留已有改动。
本轮实现已完成，没有待续测试；等待我的下一项具体需求，不自动扩展功能。
修改时保留 r41 原生/玻璃修复、r42 调度优化、r43 在线源和 DVR 契约。
设备测试必须重新核对目标、使用保护脚本并恢复本轮捕获的数据和设置。
```

工作区及相邻隔离工具路径仅适用于当前机器；新环境按开发指南配置。原始设备证据与 dist 制品不在 Git 中，缺失不代表应自动下载、重跑或重建它们。

## 当前状态与用户要求

当前应用为 0.3.2 / versionCode 6，r43 是交付标识。新增 Bilibili 视频/番剧/直播解析、按能力选集/清晰度/音轨、服务器时移与稳定页面历史恢复，详见 [Bilibili 实现与验证](BILIBILI.md)。构建及限定设备验证包括真实点播分 P、画质/音轨切换、直播一小时范围回看、暂停/继续、换画质保持内容、旋转与后台恢复、官方登录入口返回。不要自动开始新功能，先接收用户的新任务。

在线源经 AppContainer 注入，播放器仍属于 Activity 会话。不得把 CDN 地址或 Cookie 写入历史；保持初次分享别名到稳定页的历史/配置恢复、失败请求与生命周期待续请求的分离、旧播放器自动恢复避让新选择。VOD/PGC 用 Desktop UA/根 Referer 及 API 提供的优选非 P2P 地址，Live 保留独立请求契约。DVR 是正 `tmshift` 历史流，不是扩大的播放器缓存；无 PDT 时不能宣称逐帧墙钟准确。

r42 大弹窗性能优化保持：12% 模糊的弹窗绘制速率约 9.44 → 39.77 次/秒、P95 21 → 15ms，独立录像确认背景变化增多。用户偏好与音量、旋转由保护脚本恢复。

r42 的改动是 DialogBackdrop 显示对齐调度、静止场景去重，以及 LiquidGlass 的模糊背景层缩放。物理模糊半径达到 4px 才使用半宽半高，零模糊保留完整细节，文字与锐利边缘不缩小。不得把下层 Window 缓存为静态 UI，也不得把可复用的视频位图直接交给工作线程。详见 r42 说明。

r41 曾修复“实时折射关闭”时图标隐藏但点击区域保留的问题；组合与绘制共用 `glassContentReady`。用户原始玻璃开关为关闭，测试结束已恢复，不能强制打开以掩盖回归。

用户此前要求所有指定效果修复后才交付，不能用部分完成或普通透明回退宣称完成。后续修改必须保留：跳转/等待时可见 loading；视频与玻璃同步；属性/手势弹窗初现、滑动模糊、进出视频和比例切换均不闪普通透明；关闭玻璃时控件仍可见。

## 代码地图与不可回退的修复

| 入口 | 责任和约束 |
| --- | --- |
| [BilibiliMediaResolver](../app/src/main/java/com/sweefl/player/data/online/BilibiliMediaResolver.kt) | 页面/短链/剧集/直播解析、实际目录；VOD 与 Live 请求头契约分开 |
| [OnlineMediaSourceFactory](../app/src/main/java/com/sweefl/player/playback/engine/OnlineMediaSourceFactory.kt) | 不可变选中源、视频/音频合并及外部字幕保留；不向 CDN 发送 Cookie |
| [LiveReplayRules](../core/domain/src/main/kotlin/com/sweefl/player/domain/LiveReplayRules.kt) 与 [HlsDvrUriPolicy](../app/src/main/java/com/sweefl/player/playback/engine/HlsDvrUriPolicy.kt) | Long 时间与裁剪、正 tmshift、同源目录继承和返回直播去参数 |
| [OnlineChoiceDialogs](../app/src/main/java/com/sweefl/player/feature/player/ui/OnlineChoiceDialogs.kt) | 根据实际在线目录或 Media3 支持轨道选择，不展示未返回的播放能力 |
| [OpticalSeekHandoff](../app/src/main/java/com/sweefl/player/core/designsystem/OpticalSeekHandoff.kt) | 主线程交接状态机：固定旧帧、提交覆盖、执行跳转、提交新覆盖、撤去覆盖；epoch 拒绝过期回调，15 秒异常退出 |
| [VideoGlass](../app/src/main/java/com/sweefl/player/core/designsystem/VideoGlass.kt) | 单次在途 PixelCopy、生命周期、几何重映射、采样就绪和提交回调；共享 `glassContentReady` |
| [LiquidGlass](../app/src/main/java/com/sweefl/player/core/designsystem/LiquidGlass.kt) | AGSL 折射、Gaussian 模糊与独立锐利边缘；绘制门控必须遵循共享就绪规则，尤其关闭效果/API<33/纯音频 |
| [PlaybackSession](../app/src/main/java/com/sweefl/player/feature/player/PlaybackSession.kt) | 保存用户播放意图、跳转序号、相对跳转目标、引擎重建及关闭；不能把交接期间临时暂停误存为用户暂停 |
| [PlayerScreen](../app/src/main/java/com/sweefl/player/feature/player/ui/PlayerScreen.kt) | 视频覆盖层位于视频之后、控件之前；loading 包括 buffering 或 handoff.running；纯音频绕过视频采样等待 |
| [DialogBackdrop](../app/src/main/java/com/sweefl/player/core/designsystem/DialogBackdrop.kt) | 弹窗自身的下层 Window/视频合成，模糊支撑分档只增不减；滑条不能不断重建捕获会话 |
| [GlassComponents](../app/src/main/java/com/sweefl/player/core/designsystem/GlassComponents.kt) | 弹窗背景准备期间不暴露隐藏交互和语义；异常等待后有明确的非玻璃面板提示 |
| [MainRenderersFactory](../app/src/main/java/com/sweefl/player/playback/rendering/MainRenderersFactory.kt) | FFmpeg renderer 包装必须转发 Media3 的 Java 默认方法，包括首帧允许、速度、调度时长与释放 |
| [原生补丁说明](../third-party/nextlib/NATIVE-WINDOW-PATCH.md) | JNI 局部 Surface 引用不能跨调用缓存；每次取得当前 native window，平衡引用，不能吞掉 ENODEV 并报告成功 |

单独补齐 Renderer 默认方法不能修好原生后台黑屏；旧的完整回归曾持续失败。真正修复还包括本地 AAR 的 JNI 窗口生命周期补丁。不要恢复 Maven 原 AAR 来“简化依赖”。

## 构建与依赖

JDK 17、SDK/target 35、Build Tools 35.0.0、Gradle 8.13、AGP 8.9.2、Kotlin 2.1.20、Media3 1.8.0；minSdk 26，只发布 arm64。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -Command "& .\build-local.ps1 -Tasks @('verify', ':app:assembleRelease')"
python third-party/nextlib/verify_source_kit.py
```

普通机器使用 `gradlew.bat verify :app:assembleRelease`，Linux/macOS 使用 `./gradlew`。本地修改后的 [Nextlib AAR](../app/libs/nextlib-media3ext-1.8.0-0.9.0-sweefl.aar)随仓库提交；正常编译应用不需要 NDK。只有重建 JNI 适配层才需要 Windows NDK r25c 和原 AAR。详见[重建脚本](../third-party/nextlib/rebuild_native_adapter.py)及[来源披露](FFMPEG-SOURCE-0.3.2.md)。本地 AAR 不解析 Maven 传递依赖，Media3 依赖须显式保留。第三方来源套件在 Git 属性中禁止换行转换；88 项清单已按实际 Git blob 校验，不要对该目录批量格式化或归一化换行。

开发架构是 `:app` + 纯 JVM `:core:domain`。设计系统不能依赖数据层或 Media3；通过 AppContainer 显式组装。保持两个 Activity 的公开入口、Intent/Bundle 键、三份偏好文件名、URI SHA256 身份、默认 UA、字幕约定及诊断 schemaVersion 1。

## 真机验证方法与陷阱

当前实际测试机为 Redmi K20 Pro / raphael，Android 15/API35，序列号 `5ba8ddbb`，1080×2340；每次以 `adb devices -l` 为准。用户已授权本项目 root 安装/调试，不能删除用户媒体、清空应用数据或调整无关安全设置。

所有变更设备状态的回归通过[保护脚本](../tools/guarded_v032.py)，备份并恢复历史、配置、偏好、音量、旋转和自身创建的 reverse。先启动[夹具服务](../tools/fixture_server.py)；夹具目录通过 `--root` 指定，不是从仓库下载用户媒体。

```powershell
$env:SWEEFL_SERIAL='5ba8ddbb'
$env:SWEEFL_TEST_OUT='docs/testing-new-task/run01'
$env:SWEEFL_UI_SUITES='seek-epoch,surface-recreation'
python tools/guarded_v032.py
```

- 默认安装 Debug。`SWEEFL_SKIP_INSTALL=1` 保留当前包；`SWEEFL_APK` 指定其他包。不要把“跳过安装”误认为安装了刚构建的 APK。
- **Release 不写 Debug 专用诊断文件，也不接受 keepControls 等调试覆盖。** 旧诊断文件可能仍存在，不能当成 Release 的当前状态。Release 使用 `release-smoke,final-visual,modal-material,entry-material,control-visibility` 这类 UI/录像检查。
- UIAutomator 抓取可能慢于控件自动隐藏期限。可见性测试在正常暂停状态检查真实图标像素，不能只查 accessibility 节点；此前幽灵控件恰好有节点且可点击。
- 跳转位置更新不代表覆盖交接完成；最终颜色断言要等待 loading 结束。连续录像另行检查交接过程。
- 固定 ROI 必须验证横屏和有效彩色覆盖，竖屏套横屏采样不能算通过。加载圆环的薄荷色不能算玻璃旧色。
- FFmpeg 9 解码录像使用 `-fps_mode passthrough -enc_time_base 1:1000000`；不要用默认帧率转换或已删除的 `-vsync`，否则复制帧会污染首帧索引。
- 原始设备证据、偏好备份、录像及 RGB 缓冲不上传 GitHub。公开结果在[r41 说明](GLASS-CONTINUITY-r41.md)，本机证据保留在忽略目录中。

## 已验证范围与仍有限制的部分

r43 功能提交为 `862c8951ed148042884a628e1196e932bc38494b`，[对应 CI](https://github.com/847415681/Sweefl-Player/actions/runs/40195752025) 已成功。完整制品哈希、17 轮证据索引与恢复汇总见 [r43 交付快照](RELEASE-r43.md)。功能交付已收尾，夹具服务和测试作业均已停止；无需继续旧的 CDN 排障或重复全部验收。

B 站回看进程被系统终止后的重建、真实账号付费权益、PCM/听音测量没有验证。多段 durl 暂不拼接。旋转与 HOME/返回验证是同一进程内完成；不能据此宣称进程重建通过。这些是后续范围，不是自动开工清单。

r41 基线已通过完整构建、单测、lint；三渲染路径的暂停跳转、连续跳转和旋转/Home 恢复；Debug/Release 的玻璃开关可见性；Release MP4/HLS/DASH/FFmpeg 播放；两档模糊的连续跳转、弹窗首帧和拖动、进出/比例切换；慢加载转圈及恢复；纯音频控制。

这些是单台设备和指定 SDR 素材的证据。临时交接覆盖为 SDR；GPU frame-commit 不是 SurfaceFlinger 精确 latch 证明。没有声称所有设备、HDR 过渡保真、全格式、完整 16KB 页兼容或长期功耗均已验证。正式生产签名、完整无障碍和更多设备覆盖仍是后续范围，不是本轮玻璃修复的已完成成果。

## 交付与接手动作

r43 APK SHA-256：`b4542085a9b03a1d631d7dbbe1a1ddcc94c713497fe16531b56fae35784eea4d`。本次功能与验证范围见 [Bilibili 说明](BILIBILI.md)；源码包哈希以本地交付清单为准，最终 GitHub 提交以 `git log -1` 和交付回复为准。开发签名保持兼容，签名材料不进入源码包。

历史 r42 APK SHA-256：`5f2c54144f32df169d351517f7448e619e22a28031c0f1f20ab221da7c93f2d0`。本次验证范围及性能对照见 [r42 说明](DIALOG-PERFORMANCE-r42.md)，源包哈希以本地交付清单为准，GitHub 提交以 `git log -1` 和交付回复为准。CI 显式安装 `platform-tools`，修复 `setup-android@v3` 默认请求废弃 `tools` 包的问题。

历史 r41 APK SHA-256：`c2a8086a84899c93ebadb1a052e9fd06916fc6268421f6977df24f2528dd5676`；源包 SHA-256：`07ee1b3e0becfbb364a9c748552135bc0919d09e7fd3a0daa33fd3666ec4ba29`。

本机工作树通常为 `D:\Ai_Projects\sweefl-player`；工具在相邻 `.android-tools`，不是项目依赖的固定绝对路径。本地 APK/源包在被忽略的 dist 目录；上传源码、文档和构建必需的 AAR，不创建 GitHub 二进制 Release，不提交签名私钥。

新 agent 接手：执行 `git status`、`git log -1` 和 `git remote -v`；确认本文件与当前 HEAD 一致；阅读用户新要求，再选择相关实现和最小必要回归。不要自动重跑全部手机测试，不要仅凭旧摘要宣称新的改动已验证。
