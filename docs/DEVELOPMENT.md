# 开发与验证

当前 r42 增加大弹窗显示对齐采样、暂停场景去重和按物理模糊半径缩放的 GPU 传输层。实现约束、同设备前后数据及 CI SDK 包修复见 [r42 说明](DIALOG-PERFORMANCE-r42.md)；完整项目源包可用 `python tools/package_source.py --revision r42` 创建，不覆盖已有交付。

## 环境和入口

要求 JDK 17、Android SDK Platform 35、Build Tools 35.0.0。Gradle 8.13 由项目 Wrapper 固定；依赖和插件版本集中在[版本目录](../gradle/libs.versions.toml)。依赖升级与结构重构应分别验证。

已配置隔离工具的当前 Windows 工作区：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build-local.ps1
```

普通开发环境设置 `JAVA_HOME` 和 Android SDK 后：

```powershell
.\gradlew.bat verify
```

Linux / macOS：

```sh
./gradlew verify
```

`verify` 统一执行包结构 / 依赖边界检查、`:core:domain:test`、`:app:testDebugUnitTest`、`:app:lintDebug` 与 Debug APK 构建。Android 单测不启用 `returnDefaultValues`，避免 Android stub 静默返回空值掩盖错误。

快速验证纯规则或源码边界：

```powershell
.\gradlew.bat :core:domain:test
.\gradlew.bat checkArchitecture
```

[Windows 构建辅助脚本](../build-local.ps1)仅为当前进程配置工具、缓存和代理，不修改全局环境。其他机器不需要复制当前工作区的绝对路径。[CI 配置](../.github/workflows/android.yml)执行 `verify :app:assembleRelease`；远程 CI 必须在仓库托管平台实际运行后才能称为通过。

发布变体可单独检查：

```powershell
.\gradlew.bat :app:assembleRelease
```

这会运行 R8 等发布构建步骤；正式签名密钥由发布环境提供，不写入源码。

## 克隆与首次构建

```sh
git clone git@github.com:847415681/Sweefl-Player.git
cd Sweefl-Player
```

安装 JDK 17 和 Android SDK 35，设置 `JAVA_HOME` 与 `ANDROID_HOME`，或在本机 `local.properties` 中配置 `sdk.dir`（不要提交该文件）。Windows 使用 `gradlew.bat`；Linux/macOS 使用 `./gradlew`。无需 root 即可运行应用。仓库不含签名密钥或 APK，第三方源码及许可证保留在 `third-party`。

## r40 渲染与网络实现

- `core/designsystem/ComposeBackdrop.kt`：列表内容的 GPU 图层复用；浮层必须与来源分离，避免自捕获递归。
- `core/designsystem/VideoGlass.kt` 与 `DialogBackdrop.kt`：视频采样生命周期、跳转代次及弹窗局部合成。不可为隐藏延迟复用过期视频帧。
- `playback/network/ParallelHttpDataSource.kt`：有序 Range 分块、强 ETag 校验、共享加速请求预算和单一线程关闭所有权。普通音视频轨道连接不占用整段加速额度，避免并发设为 1 时互相饥饿。
- `domain/NetworkPlaybackSettings` 与设置界面：1–32 并发，5–600 秒缓冲目标。64 MiB 是 Media3 分配器目标，不是进程总内存上限；下载分块窗口最多每数据源 8 MiB。无离线磁盘缓存。
- HLS/DASH 的大资源可按 Range 加速，不包含清单级多分片并行预取；小资源、无强 ETag 或不支持 Range 时兼容回退。

设备契约测试使用自定义 `android.app.Instrumentation`，无需新增测试框架：

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
# 先安装匹配的 Debug 应用；仅在已授权的测试设备使用现有测试保护脚本。
$env:SWEEFL_SERIAL='你的设备序列号'
python tools/network_transport_contract_check.py
```

测试脚本需要 Python；图像分析额外需要 numpy/Pillow，录像解码需要 FFmpeg。部分自动化依赖已授权 root，不能直接对未授权设备执行。原始设备数据不随 Git 仓库提交；公开测试结论见 [r40 说明](RELEASE-r40.md)。

## r41 玻璃与原生适配层维护

另一个 agent 接手先读[交接文档](AGENT-HANDOFF.md)，实现地图和已验证边界见[r41 说明](GLASS-CONTINUITY-r41.md)。

- 应用已改用仓库内的[修正 Nextlib AAR](../app/libs/nextlib-media3ext-1.8.0-0.9.0-sweefl.aar)。普通应用构建不需要 NDK；不要同时加入 Maven 原 Nextlib，避免重复类或丢失 Surface 修正。
- 只有重建 JNI 适配层需要 Windows NDK r25c。按[原生重建说明](../third-party/nextlib/NATIVE-WINDOW-PATCH.md)运行；原 Java 类和 FFmpeg 库不变。修改来源说明或原生材料后同步更新完整性清单，执行 `python third-party/nextlib/verify_source_kit.py`。
- `glassContentReady` 同时用于上层显隐与绘制：不能让关闭效果/API<33/纯音频等待不会产生的背景。可见性必须检查实际像素及交互，不能只检查 UI 节点。
- 跳转交接由 `OpticalSeekHandoff` 协调，并保留用户播放意图。不能用固定延迟或删除 loading 来掩盖同步问题。
- Debug 诊断用于 `seek-epoch,surface-recreation`；Release 不写诊断文件且忽略调试 keepControls。Release 使用 `release-smoke,final-visual,modal-material,entry-material,control-visibility`；慢 UI 抓取时先暂停，避免自动隐藏造成假失败。
- 完整构建命令：`powershell -NoProfile -ExecutionPolicy Bypass -Command "& .\build-local.ps1 -Tasks @('verify', ':app:assembleRelease')"`。只改文档或测试说明时无需重复手机回归；代码变化按影响范围选择检查。

## 修改代码

先阅读[架构指南](ARCHITECTURE.md)，再按职责放置文件：规则进入 JVM 模块；查询和存储进入数据层；Android 生命周期留在入口；功能状态放 Route / ViewModel / 播放会话；渲染与用户操作放 UI。根包仅保留 Application 和两个系统入口。

依赖由[容器](../app/src/main/java/com/sweefl/player/di/AppContainer.kt)创建并通过构造参数或函数参数传入。仓库接口用于真实的替换和测试需求。不要在 Composable 中创建 SharedPreferences、直接扫描 MediaStore，或从任意功能代码向下强转 Application 取得容器。

新增播放能力时明确所有权：引擎属于单个 Activity 会话；后台作业、监听器和播放器须在所有者退出时关闭。跨解码器重建使用原始进度，不能误用观看历史的片尾归零规则。字幕和 PiP 修改必须做真机验证。

保持应用 ID、入口类名、Intent / Bundle 键、SharedPreferences 文件名和 URI 摘要算法兼容。需要改变持久化格式时，先设计迁移和旧数据测试。新增 UI 参数优先使用命名参数；大段平台操作从 UI 提取到适配器。

[编辑器约定](../.editorconfig)和[换行约定](../.gitattributes)统一 UTF-8、LF 与缩进。新增代码采用 Kotlin 官方风格；引入格式化器或新架构依赖时明确版本、收益及验证成本。

## 测试与报告

- [规则单测](../core/domain/src/test/kotlin/com/sweefl/player/domain/)覆盖 URI / UA、软件解码器识别、进度、流 MIME 等。
- [应用单测](../app/src/test/java/com/sweefl/player/)使用可替换仓库验证权限撤销、查询取消和刷新状态，并固定持久键、Media3 MIME 契约。
- [架构检查](../gradle/architecture.gradle.kts)对源码依赖方向设置约束；[反向验证脚本](../tools/architecture_guard_test.py)临时生成越层依赖，验证拒绝后移除探针。运行期间不要同时编译 APK。
- 真机覆盖系统文件选择器、原生 SurfaceView、MediaCodec、PiP 和外置字幕。固定素材和窗口的结果只代表这些测试条件。

测试报告由 Gradle 写入各模块的 `build/reports`。设备证据使用独立版本目录，保留失败和修复经过；新的测试不得覆盖[0.2.0 历史证据](testing-v020/)。测试工具入口和参数见[工具说明](../tools/README.md)。

## 合入与发布检查

提交前执行 `verify`，检查修改涉及的真机路径，更新架构 / 使用说明。Git 提交应围绕完整职责，避免将生成的缓存、UI 转储、签名密钥或本机 SDK 路径加入源码。

Debug APK 供开发验证。正式发布还需要正式签名、发布配置验证、目标设备覆盖与产品验收。版本名和 versionCode 位于[应用构建配置](../app/build.gradle.kts)，默认 UA 是独立兼容契约，不随展示版本自动覆盖用户配置。
