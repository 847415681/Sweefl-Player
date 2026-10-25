# Agent 交接：Sweefl Player r41

更新：2026-10-25。本文件是新 agent 的入口；先读本页，再读[开发指南](DEVELOPMENT.md)、[架构指南](ARCHITECTURE.md)和[r41 验证说明](GLASS-CONTINUITY-r41.md)。用户已要求将本次完整修复和文档提交到指定 GitHub 仓库。

## 当前状态与用户要求

当前应用为 0.3.2 / versionCode 6，r41 是交付标识。已完成并真机验证本轮玻璃连续性及控件不可见回归，优化 Release 已留在手机；没有待恢复的自动任务、测试服务器或本轮创建的 ADB reverse。不要自动开始新功能，先接收用户的新任务。

最新问题的实际触发条件是“实时折射关闭”：不创建视频采样，但绘制层仍要求采样非空，导致图标隐藏、点击区域保留。现在组合与绘制共用 `glassContentReady`。用户原始玻璃开关为关闭，测试结束已恢复；不能通过强制打开玻璃来掩盖此问题。

用户此前要求所有指定效果修复后才交付，不能用部分完成或普通透明回退宣称完成。后续修改必须保留：跳转/等待时可见 loading；视频与玻璃同步；属性/手势弹窗初现、滑动模糊、进出视频和比例切换均不闪普通透明；关闭玻璃时控件仍可见。

## 代码地图与不可回退的修复

| 入口 | 责任和约束 |
| --- | --- |
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

已通过完整构建、单测、lint；三渲染路径的暂停跳转、连续跳转和旋转/Home 恢复；Debug/Release 的玻璃开关可见性；Release MP4/HLS/DASH/FFmpeg 播放；两档模糊的连续跳转、弹窗首帧和拖动、进出/比例切换；慢加载转圈及恢复；纯音频控制。

这些是单台设备和指定 SDR 素材的证据。临时交接覆盖为 SDR；GPU frame-commit 不是 SurfaceFlinger 精确 latch 证明。没有声称所有设备、HDR 过渡保真、全格式、完整 16KB 页兼容或长期功耗均已验证。正式生产签名、完整无障碍和更多设备覆盖仍是后续范围，不是本轮玻璃修复的已完成成果。

## 交付与接手动作

r41 APK SHA-256：`c2a8086a84899c93ebadb1a052e9fd06916fc6268421f6977df24f2528dd5676`。源包 SHA-256：`07ee1b3e0becfbb364a9c748552135bc0919d09e7fd3a0daa33fd3666ec4ba29`。本轮 GitHub 交接文档是在该本地源包交付之后补充的，远端提交比原源包多文档更新；应用代码及 APK 不因此变化。GitHub 的最终提交 SHA 以 `git log -1` 和本次交接回复为准。

本机工作树通常为 `D:\Ai_Projects\sweefl-player`；工具在相邻 `.android-tools`，不是项目依赖的固定绝对路径。本地 r41 APK/源包在被忽略的 dist 目录；本次上传源码、文档和构建必需的 AAR，不创建 GitHub 二进制 Release，不提交签名私钥。

新 agent 接手：执行 `git status`、`git log -1` 和 `git remote -v`；确认本文件与当前 HEAD 一致；阅读用户新要求，再选择相关实现和最小必要回归。不要自动重跑全部手机测试，不要仅凭旧摘要宣称新的改动已验证。
