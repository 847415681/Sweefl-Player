# Sweefl Player 开发约定

- 目标：Android ARMv8-A / arm64-v8a，原生 Kotlin + Compose + Media3。MX Player 作为交互参考，液态玻璃作为视觉方向。MPC-HC 在相邻目录作为功能参考，当前未复用其 Windows 核心。
- 工作区已有隔离 Android 工具。优先通过 `powershell -NoProfile -ExecutionPolicy Bypass -File .\build-local.ps1` 构建，并检查单元测试和 lint。
- 用户明确说明测试手机的 shell 已授予 root，并授权后续用 root 自动执行开发/测试操作。本次 `adb shell su -c id` 已验证 `uid=0(root)`（KernelSU）。连接的型号为 2604FRK1EC，Android 16，arm64-v8a；每次操作仍先用 `adb devices -l` 确认当前目标。
- 普通 `adb install` 被系统 USB 安装限制拒绝时，可将本项目 APK 推送到 `/data/local/tmp/`，再用 `su -c 'pm install -r ...'` 安装。无需为相同的、已授权的项目测试操作反复询问。
- root 授权用于本项目安装、调试和测试，不代表可删除用户媒体、清空其他应用、修改全局安全设置或执行无关操作。保留现有应用数据，测试媒体使用明确标识的独立文件。
- 测试结论需区分构建、静态检查与真机验证；不得把一种手机或一种编码的结果泛化为全部设备/格式。
- 架构采用 `:app` + 纯 JVM `:core:domain`。应用内按 `feature`、`data`、`playback`、`core`、`di` 组织；详细职责见 [架构指南](docs/ARCHITECTURE.md)，开发入口见 [开发指南](docs/DEVELOPMENT.md)。
- 根包仅保留 `MainActivity`、`PlayerActivity` 和 `SweeflApplication`。两个 Activity 的公开入口名、Intent / Bundle 键、偏好文件名及 URI 摘要规则是兼容契约，修改时必须有迁移与回归依据。
- 依赖由 AppContainer 组装后显式传入；UI 不直接操作 SharedPreferences 或 MediaStore，设计组件不依赖数据层。播放器实例属于 Activity 会话，不能放进应用级容器或跨 Activity 生命周期持有。
- 默认构建入口运行 `verify`，包含架构检查、两个模块的单元测试、Debug 构建和 lint；发布改动另检查 `:app:assembleRelease`。不要仅运行 Android 单测而漏掉纯规则模块。
- 依赖版本集中在 [版本目录](gradle/libs.versions.toml)。新增行为测试聚焦契约、取消和生命周期；避免以 Android stub 默认空返回值掩盖错误。
- 设备测试使用版本独立的输出目录，保留原失败及复测证据；参考 [工具说明](tools/README.md)。测试后恢复音量和临时设置，移除自己创建的 ADB reverse。不要覆盖历史版本证据。
