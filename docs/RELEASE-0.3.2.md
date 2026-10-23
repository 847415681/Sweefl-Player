# Sweefl Player 0.3.2 交付验证记录

## 产物

- ARM64 Release，versionName 0.3.2 / versionCode 6，min26 / target35。
- APK：[本地安装包](../dist/Sweefl-Player-0.3.2-arm64-r38.apk)，SHA256 `b4db4bcedc436d1e18e0658701671881307fd1e2edb9087e7f3c0f630ac2d211`。
- Release经过R8压缩；沿用本地Android Debug证书签名以保留现有安装数据，v2/v3签名验证通过。这不是应用商店生产签名。
- 所有者已同意GPLv3本地交付，详见[分发说明](../DISTRIBUTION-NOTICE.md)。不公开上传源码或签名私钥。

## 构建与真机证据

最终完整`verify :app:assembleRelease`通过，104tasks；架构检查69个Kotlin文件、两模块单元测试、Debug、lint与Release均包含。已有Windows符号链接测试跳过项不能描述为全部执行。

测试机：Redmi K20 Pro / raphael，Android15/API35。以下不是跨设备/全格式保证。

| 范围 | 结果与证据 |
|---|---|
| 长按与沉浸 | [r20](testing-v032/k20-r20-final-regression)：默认2×、3×配置、恢复1.5×、取消、PiP、锁定、顶部提示均通过 |
| 连续模糊 | r20：0/50/100持久化，图像梯度能量1330.75 / 0.428 / 0.320，背景明显平滑而前景保持锐利 |
| 字幕 | [r22](testing-v032/k20-r22-subtitles)：±60s、暂停刷新、较早未访问cue、VTT重叠、重启持久化、EOF不延长、固定滑轨通过 |
| 解码与色彩 | r22：FFmpeg AVC/HEVC/VP9，PQ/HLG转系统视频；SDR/PQ/HLG增强图实际建立与原生回退通过 |
| 跳转与生命周期 | r12三路径暂停红绿蓝及连续跳转；r15三路径暂停旋转、桌面返回通过 |
| 采样频率 | r12：30fps片源10秒301帧/0丢帧，玻璃29.83–30.21次/秒，无120fps宣称 |
| 弹窗遮挡 | [横屏截图](testing-v032/k20-r18-dialog/dialog-backdrop/screenshots/dialog.png)：后方进度条/按钮连续透出；[亮度对比](testing-v032/dialog-dim-comparison.json)由内252外100变为内外100 |
| 网络与手势 | [r28](testing-v032/k20-r28-codec-workaround)：默认/自定义双击、取消、重启、MP4/HLS/DASH真实预览及资源释放通过 |
| 错误重试 | [r27](testing-v032/k20-r27-retry/retry/retry-result.json)：404→恢复夹具→重试→清错并出帧通过 |
| 实际Release | [r31](testing-v032/k20-r31-release-smoke)：签名Release MP4/HLS/DASH播放/暂停进程存活；HLS截图确认主视频出图 |

## 已修复的设备特例

r26发生真实SIGSEGV：该设备特定Android15镜像在HLS AAC配置时于系统`libsfplugin_ccodec.so / CCodecConfig::getReflectedFormat`空指针崩溃。现在仅完整系统指纹匹配时优先使用FFmpeg音频，视频路径不变。r28及Release r31复测通过。保留[原崩溃](testing-v032/k20-r26-network-retry/codec-crash.txt)，不修改系统库或安全设置。

## 视觉边界

平台elevation阴影已移除，避免透明层显现时暴露投影的内部多边形；整个控件子树的重复黑色绘制也已移除，模糊/反光shader分离。r32–r37严格测试发现暂停首跳旧色并保留失败证据：暂停时500ms检查晚于跳转。最终r38改为逐显示帧轻量检查版本号、PixelCopy仍去重，跳转先失效再延后80ms执行并累计待执行相对目标；新输出还须满足提交证据和200ms透明稳定窗口。r35三路径暂停与快速跳转通过；r38签名Release的0档186帧、50档359帧逐实际录制帧检查，三个按钮和底栏未检测到错误主色。普通VFR录屏及ROI检测不是每个面板周期/任意形状的形式证明；自然镜头切换仍可能有异步PixelCopy采样延迟。

提交证据不是精确合成器PTS；HDR→SDR不是面板色彩校准，也不承诺所有DV/DRM。FFmpeg原生视频是8bit YV12，非HDR直通；Windows LAV/DirectShow/MPC核心不能在Android直接加载。完整实现边界见[玻璃与交互](GLASS-AND-GESTURES-0.3.2.md)。

## 恢复及最终状态

各测试guard按每次开始时的状态恢复三项偏好、所有者、媒体音量、wm旋转和自建reverse。r29安装后检查三偏好均一致；其当次音量64，与最初基线68不同，不能擅自把后来用户状态改回旧基线。

最终r38签名版已安装，MP4/HLS/DASH播放暂停通过。r38三偏好恢复均为true，当次音量76已恢复；最终wm旋转lock 0，reverse列表为空。三个早期自建录屏逐个与本地SHA256一致后清除，未删除用户媒体。源码最终包为Sweefl-Player-0.3.2-source-r38.zip，包含项目、构建入口、完整保留的Nextlib/FFmpeg及相关依赖源码与许可证，不含签名密钥或本地偏好。旧无r38后缀产物为历史候选，勿作为本次交付。最后锁屏验证单独记录在docs/testing-v032/final-device-state.txt，不需唤醒设备查阅。
