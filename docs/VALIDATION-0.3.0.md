# Sweefl Player 0.3.0 验证报告

本版完成统一液态玻璃控件、横向拖动跳转、可关闭的真实进度预览，以及左/中/右三区独立双击配置。已覆盖安装到授权 REDMI K90 Max（2604FRK1EC，Android 16 / API 36，arm64-v8a），已安装 APK 与交付文件 SHA-256 一致。

## 交付与构建

- [ARM64 Debug 安装包](../dist/Sweefl-Player-0.3.0-arm64-debug.apk)，versionName `0.3.0`，versionCode `4`，20,869,416 字节。
- [SHA-256 文件](../dist/Sweefl-Player-0.3.0-arm64-debug.sha256.txt)：`BBD70883B8F13B6F4D03A7C78616F63FA2AB2AFA1369B3EA4F7374B6EE96E5B9`。
- `verify` 通过：45 个 Kotlin 文件的架构边界检查、两个模块合计 **32 项单元测试**（失败/错误/跳过均为 0）、Debug 构建、lint。
- lint：**0 error / 0 fatal / 42 warning**，并非零警告。
- `:app:assembleRelease` 与 R8 优化构建通过。Release 是未签名构建产物，未在设备上运行；本报告的设备证据对应交付的 Debug 包。
- APK 仅包含 `arm64-v8a` 原生库，已检查其中包含参考项目 MIT 许可证。

原始汇总：[构建证据](testing-v030/build-final.json)、[最终设备与交付摘要](testing-v030/validation-summary.json)。

## 真机结果

最终包共有 **62 条设备断言通过**。这些是具体行为检查的数量，不表示覆盖 62 种设备或格式。

| 分组 | 断言 | 验证内容 | 原始结果 |
| --- | ---: | --- | --- |
| 手势与预览主回归 | 28 | 默认三区、独立绑定、NONE、±30 秒限幅、进程重启持久化、恢复默认、横滑提交/取消、预览关闭无辅助引擎、MP4/HLS/DASH 实际预览与释放 | [结果](testing-v030/release-check-gestures/glass-gesture-results.json) |
| 预览与生命周期 | 16 | 常规本地 URI、稀疏帧回退、A→B→A、就绪后后台释放、无残留预览、自定义控制显隐/锁定、纯音频回退、HDR 主视频继续出帧 | [结果](testing-v030/release-check-preview/glass-gesture-results.json) |
| 旧交互回归 | 8 | 音量、窗口亮度、锁定/解锁、片尾附近切换系统软件解码仍保留暂停进度、PiP 持续出帧并隐藏控件、横屏玻璃播放 | [结果](testing-v030/release-check-legacy/interaction-results.json) |
| 弹窗与布局 | 8 | 设置、共享手势入口、网络输入及键盘焦点、操作按钮可达、竖屏三区、横屏嵌套动作与最后一项倍速可达 | [结果](testing-v030/release-check-dialogs/glass-gesture-results.json) |
| 横屏键盘专项 | 2 | 实际横屏且键盘开启时 URL 可编辑并保留焦点，播放按钮仍可见 | [结果](testing-v030/final-landscape-keyboard/glass-gesture-results.json) |

主回归中的网络 MP4 预览：主视频在拖动期间保持 `2134ms`，目标帧在小窗口显示为约 23 秒，松手才提交主视频跳转；HLS、DASH 和受控常规帧率本地 MP4 也通过相同检查。预览没有用主播放器 seek 或装饰 PixelCopy 冒充目标画面。

[A→B→A 日志](testing-v030/release-check-preview/rapid-retarget.log)记录了已验证 23 秒帧的复用，避免重复同位置 seek 不出新帧而超时。[后台日志](testing-v030/release-check-preview/background-preview.log)记录已显示真实帧后退出前台，辅助引擎释放至 `active=0`。图片只在解码释放时间戳与 SurfaceTexture 锁存时间戳精确一致、实际帧位于目标校验范围内时显示。

旧交互检查中，暂停位置 `39790ms` 在切换到 `c2.android.avc.decoder` 后仍是 `39790ms`；PiP 呈现帧计数从 53 增至 113。HDR10 测试源的 HEVC 10-bit / PQ 格式仍由主视频原生链路呈现并持续出帧；这不是 HDR 色彩准确度认证。

## 视觉检查

所有应用内交互控件使用共享玻璃组件；设置、手势动作、网络输入、倍速、字幕/音轨、播放信息等弹窗使用相同表面与按钮。人工检查了以下最终截图的层次、文字可读性、触控布局及裁切：

- [横屏播放及中央暂停按钮](testing-v030/release-check-legacy/screenshots/landscape-refraction.png)
- [真实目标预览窗口](testing-v030/release-check-gestures/screenshots/verified-mp4.png)
- [三区与预览配置](testing-v030/release-check-gestures/screenshots/gesture-settings-portrait.png)
- [播放信息](testing-v030/release-check-preview/screenshots/glass-details.png)与[字幕/音轨](testing-v030/release-check-preview/screenshots/glass-tracks.png)
- [横屏键盘适配](testing-v030/final-landscape-keyboard/screenshots/landscape-network-ime.png)
- [横屏嵌套动作](testing-v030/release-check-dialogs/screenshots/landscape-nested-actions.png)与[倍速](testing-v030/release-check-dialogs/screenshots/speed.png)

系统键盘、权限、文件选择器和 PiP 系统菜单保留系统外观。截图中的玻璃装饰和预览都是 SDR，不能证明 HDR 显示还原。

## 已处理的问题与保留的失败证据

1. 初始 4MiB 请求限制不足以跨越 1080p 长 GOP 前滚，已调整为正常媒体响应读取每请求 8MiB / 每次拖动 24MiB；仍有超时和降级。
2. 本机 TextureView 更新回调先于 RenderThread 锁存，回调时纹理时间戳为 0。修复为当前请求期限内等待实际锁存，保持精确时间戳校验，不放宽为“收到回调就展示”。[早期失败](testing-v030/preview-third.log)与[首次成功](testing-v030/preview-fourth.log)均保留。
3. 横屏键盘会挤掉旧弹窗正文。改为系统/键盘边距只消费一次；横屏正文和操作按钮并排，极矮空间暂时收起标题，保留输入节点和焦点。[原失败截图](testing-v030/final-dialogs/screenshots/network-ime.png)与最终专项结果分别保留。
4. 原有本地测试视频约 0.46fps，13.5 秒目标的下一帧在约 15.85 秒，超出校验范围。它正确回退为时间提示。另创建常规帧率测试文件验证本地预览，结束后删除并验证媒体索引消失；没有用修改断言把原片假装成精确预览成功。
5. 自动化还修正了复合 root 命令引用、双击间隔、截图文件名中的斜杠、残留触摸及固定竖屏坐标假设；原失败目录保留，最终结果使用独立 `release-check-*` 目录。

## 数据与测试环境收尾

[初次升级](testing-v030/upgrade.json)及[最终安装](testing-v030/upgrade-release-check.json)均比较了覆盖安装前后三份偏好文件的内容摘要，安装过程未改写它们。未卸载、未清空应用数据、未删除用户媒体或修改系统安全设置。

整轮测试结束后，`player_preferences` 已恢复到最初备份，媒体音量恢复为 0，临时屏幕旋转恢复为 `lock 0`。测试创建的 MP4 及媒体索引已清理；原 529 测试视频保留。本任务创建的 8765 reverse 已移除，本地素材服务器已停止。

整个开发/测试跨度内，历史和网络配置摘要与最初不同，包含测试播放/网络配置写入；未将这些文件强制回滚或清空，因此不声称整轮测试后这两份文件逐字不变。安装兼容验证与“恢复用户偏好”是不同的结论，具体布尔结果完整记录在最终摘要中。

## 实现边界

图片预览增加解码和网络开销，可在设置关闭。直播、多 period 流、目标附近无可验证帧、超时或解码资源不足会退回时间提示。读取计数不包含全部 HTTP 错误响应体和编解码器内存，不是整个进程的硬资源上限。Android 13 以下使用统一半透明玻璃回退，本轮未在旧系统实机验证；不推广到所有设备、编码、DRM、16KB 页或长时间续航。

参考项目来源、固定版本、许可、原生适配与完整交互规则见 [0.3.0 功能说明](LIQUID-GLASS-0.3.0.md)。
