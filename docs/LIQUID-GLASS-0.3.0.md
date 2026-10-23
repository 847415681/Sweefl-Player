# 0.3.0：液态玻璃与播放交互

## 使用变化

- 首页、底部导航、中央播放/暂停、快进/快退、播放工具栏、输入框、开关、进度条以及应用内弹窗统一使用玻璃组件。
- 播放区横向拖动先暂存目标，松手后跳转；取消拖动不提交。方向在越过触摸阈值后锁定，保留左侧上下调窗口亮度、右侧上下调音量。
- 默认双击：左侧后退 10 秒、中间播放/暂停、右侧快进 10 秒。三个区域各占宽度三分之一，可分别选择不操作、播放/暂停、前后 10/30 秒、显示/隐藏控件、锁定触控。
- 首页设置与播放工具栏的手势按钮都可打开“手势与进度预览”。预览默认开启，关闭后拖动只显示时间，不创建辅助解码器或发起预览请求。
- 进度条拖动与横向拖动共享预览窗口；窗口位于底部进度控件上方。矮横屏缩小窗口，并在拖动时隐藏标题栏，进度条自身不移动。
- 应用弹窗统一处理系统栏和键盘边距；横屏将正文与操作按钮并排，在键盘占据大部分高度时收起标题，保持输入焦点和可滚动字段。
- 系统文件选择器、权限对话框、键盘和系统画中画菜单由 Android 提供，保留系统样式。

## 参考来源与原生适配

参考 [shuding/liquid-glass](https://github.com/shuding/liquid-glass)，固定研究版本 `a2d2e847f793430e3409a52927af815a23f4d372`。参考实现使用圆角矩形距离场、平滑的中心方向位移，以及 SVG/CSS backdrop filter；本应用将这个视觉思路适配为 Android 13+ 的 AGSL。

原生实现使用像素空间圆角、有上限的边缘位移、明暗轮廓与跟随触摸位置的高光。参考项目的 8 位位移图会裁切位移，本实现采用浮点坐标，不能视为逐像素移植。边缘高光是应用自行设计的视觉增强，不宣称是参考项目中的物理光学算法。作者版权与完整 MIT 许可打包在应用资产 [liquid-glass-LICENSE.txt](../app/src/main/assets/liquid-glass-LICENSE.txt) 中。

首页使用同一解析场景生成背景和玻璃采样，不能折射任意列表内容。播放时保留主视频的原生 SurfaceView；所有可见控件及其弹窗共享一次可见视频区域的 SDR PixelCopy，采样尺寸不超过 480×320，播放时最多 8fps、暂停时最多 2fps。一个帧着色器供所有控件复用，采用线性过滤；隐藏控制层、进入画中画或 Activity 停止时停止取样。文字弹窗使用更深的衬底。Android 8–12、关闭实时折射或无法取样时使用统一的半透明渐变与轮廓。

这层装饰采样和预览均是 SDR 界面内容；主视频 HDR SurfaceView 没有被替换，界面截图不能证明 HDR 色彩或动态元数据还原。

## 真实目标预览

`PlaybackSession` 提供不可变的 `PreviewSource`，携带主视频的 URI、MIME 与 User-Agent。功能 UI 仅在拖动且开关开启时创建窗口；`playback/preview` 持有独立的暂停播放器，禁用音频和字幕、不取得音频焦点、不写历史，也不接入主播放诊断。

请求使用 500ms 时间格与 150ms 防抖；最多一个请求在途，只保留最新待处理目标。相同目标复用已验证帧，包括 A→B→A 返回原目标的情况。图片时间显示实际验证的帧时间，不把请求时间冒充解码结果。

验证路径：

1. 记录 Media3 `VideoFrameMetadataListener` 返回的 period 相对时间及 `releaseTimeNs`。
2. 加上 period 的窗口偏移，检查实际帧处于目标附近（提前不超过 100ms、滞后不超过 500ms）。
3. 等待 TextureView 对应纹理真正锁存，要求 `SurfaceTexture.timestamp == releaseTimeNs`。设备的 UI 更新回调可能早于 RenderThread 锁存，不能把下一次回调当作成功证据；只在当前请求期限内进行短间隔复核。
4. 确认请求仍是最新目标后才解除遮挡。旧请求可完成但不能覆盖新目标；超时或失败关闭本次预览引擎，保留时间提示。

这些时间语义已对照 Media3 1.8.0 的 [MediaCodecVideoRenderer](https://github.com/androidx/media/blob/1.8.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/video/MediaCodecVideoRenderer.java) 与 Android [MediaCodec](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/media/java/android/media/MediaCodec.java) 实现核对。预览自行持有 SurfaceTextureListener，并以 `setVideoSurface` 连接，避免 `setVideoTextureView` 覆盖监听器。

松手、取消、关闭窗口、源替换和 Activity `ON_STOP` 都释放辅助资源。时间戳无法验证、无视频轨道、超时、解码资源不足等情况显示“预览暂不可用”，不展示旧画面。当前仅对稳定的单 period 点播启用图片预览；直播和多 period 流保守降级，不声称通用 DRM、所有编码或所有设备均支持。

辅助播放器优先选择低码率/小尺寸视频轨道，缓冲目标 2MiB、时间阈值 0.5–1.5 秒，连接/读取超时 2 秒，请求期限 3 秒。正常媒体响应传输计数上限为每请求 8MiB、每次拖动 24MiB，按回调粒度停止。它们是加载限制，不是整个进程或实际线路流量的硬上限：HTTP 头、Media3 HTTP 错误响应体、编解码器/纹理等内存不受该计数器限制。单码率文件仍需解码原始尺寸，长 GOP、慢网络和资源紧张时可能降级。

## 配置兼容与验证

沿用 `player_preferences`，新增 `seek_preview`、`double_tap_left`、`double_tap_center`、`double_tap_right`；动作保存稳定枚举名，未知值回退默认。应用 ID、Activity 入口、旧 Intent/Bundle 键、历史、UA/MIME 存储、URI 摘要与主播放诊断 schema 保持兼容。

新的证据集中放在 [testing-v030](testing-v030/)。初次覆盖安装前后比较了三份偏好文件摘要，[upgrade.json](testing-v030/upgrade.json) 记录原数据保持一致。保留初始流量上限导致的失败、纹理时间戳为 0 的超时，以及修复后的复测；不覆盖旧版本证据。

构建检查仍使用 `verify`（架构、两个模块单测、Debug、lint），发布前另执行 `:app:assembleRelease`。设备回归入口为 [glass_gesture_regression.py](../tools/glass_gesture_regression.py)，测试专用 URI/本地服务器，结束后恢复用户偏好与音量。最终构建、真机结果和安装包摘要以本目录最终验证汇总为准。
