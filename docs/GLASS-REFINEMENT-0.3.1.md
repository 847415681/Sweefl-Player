# 0.3.1：液态玻璃层级与布局调整

本版针对进度条、底板和按钮的大面积重复玻璃进行收敛。版本 `0.3.1` / code `5`，已覆盖安装到授权测试手机。

## 设计依据

阅读了用户提供的 SwiftUI Liquid Glass 指南及其参考文档。包内指南署名来源为 Dimillian/Skills，属于 SwiftUI 实践资料；另核对了 [Apple HIG — Materials](https://developer.apple.com/design/human-interface-guidelines/materials)。Apple 将 Liquid Glass 定位为浮于内容之上的控制与导航层，并明确建议内容层使用普通材质。

采用的原则是控制分组、明确主次、统一形状与留白，以及让交互状态保持可辨识。Android 端通过 Compose、共享表面和现有 AGSL 对应这些原则；SwiftUI 原生 API、系统材质合并形变不属于本次实现。

## 具体变化

| 区域 | 0.3.1 处理 |
| --- | --- |
| 进度条 | 去掉整条轨道的玻璃胶囊及滑块镜片，改为 4dp 细轨道、20dp 圆形滑块；保留至少 48dp 的拖动区域和原有进度语义 |
| 底部工具区 | 共享一块玻璃，内部五个按钮不再分别折射和描边；横屏最大宽度 640dp，图标居中成组，减少横向散布 |
| 顶部工具 | 信息、PiP、锁定共享同一胶囊；返回与中央播放组保持独立动作层级 |
| 弹窗 | 保留单层玻璃底板，内部文字动作、开关、输入框使用轻量状态；保持滚动和横屏键盘适配 |
| 媒体库 | 继续播放、视频与文件夹卡片回归普通内容表面；底部导航共享玻璃，选中状态用轻量色块区分 |
| 材质 | 降低边缘亮度、描边、饱和度和阴影；大表面的位移更克制，加入邻域柔化，减少彩色轮廓重复与背景波浪 |

底部区域由底板、轨道、滑块和五个按钮共 **8 个独立光学表面**收敛为 **1 个共享表面**。这是表面实例数，不表示旧版每个像素都叠了八层。

![底部控件前后对照](testing-v031/controls-comparison.png)

对照图裁取自相同测试素材的实机截图，仅缩放并添加说明；完整 [旧版播放页](testing-v030/release-check-legacy/screenshots/landscape-refraction.png)和[新版播放页](testing-v031/legacy/screenshots/landscape-refraction.png)均保留。

其他截图：[媒体库](testing-v031/screenshots/library.png)、[手势设置](testing-v031/dialogs/screenshots/portrait-gestures.png)、[真实目标预览](testing-v031/r2/gestures/screenshots/verified-mp4.png)、[横屏键盘](testing-v031/r2/keyboard/screenshots/landscape-network-ime.png)。已人工检查这些界面的材质层次、文字、控件间距和裁切。

## 实现与兼容

[共享控件](../app/src/main/java/com/sweefl/player/core/designsystem/GlassComponents.kt)中的 `GlassSurface` 提供材质归属，内部按钮读取 `LocalInsideGlass`，避免各自创建镜片；[光学层](../app/src/main/java/com/sweefl/player/core/designsystem/LiquidGlass.kt)负责更柔和的采样、折射和轮廓。

未改动播放器引擎、预览解码器、手势规则、偏好键或公开入口。细轨道仍由原 Material Slider 承担触摸与无障碍进度语义，Canvas 只替换轨道外观。主视频保持原生 SurfaceView；装饰与截图仍为 SDR。Android 13 以下保留半透明回退。本轮没有量化续航或对所有设备、字体缩放、HDR 色准做泛化结论。

## 验证

- `verify`、`:app:assembleRelease` 通过；架构检查通过。
- 两个模块合计 **32 项单元测试**通过，无失败、错误或跳过。
- lint：**0 Error / 0 Fatal / 42 Warning**。
- 授权 REDMI K90 Max / Android 16 实机共 **46 条断言**通过：弹窗 8、旧交互 8、手势与预览 28、横屏键盘 2。
- 覆盖横滑提交/取消、三区独立配置与重启持久化、预览关闭、MP4/HLS/DASH 真实帧、资源释放、音量亮度、锁定、解码切换、PiP、横竖屏菜单与键盘焦点。
- Debug 安装包的手机端 SHA-256 与交付文件一致。R8 Release 仅验证构建，未做 Release 实机运行。

[构建原始记录](testing-v031/build-final.json) · [最终汇总及分组证据](testing-v031/validation-summary.json)。

初次手势脚本在最后一次选择后立即读取偏好文件，保存断言失败。补充“选择弹窗返回”的同步确认与实际绑定值记录后，同一 APK 通过完整手势组；没有放宽绑定值断言，也没有为此修改应用代码。[初次失败](testing-v031/gestures/glass-gesture-results.json)及[复测结果](testing-v031/r2/gestures/glass-gesture-results.json)分别保留。

本轮测试先备份全部三份偏好文件，结束后恢复并逐份比较，包含历史、网络配置及用户设置；媒体音量和临时旋转设置也已恢复。原用户媒体保留，测试专用 reverse 已移除，本地素材服务已停止。[首次恢复](testing-v031/restoration.json)与[复测恢复](testing-v031/r2/restoration.json)提供具体结果。旧版 APK 未被覆盖。

## 交付

[ARM64 Debug APK](../dist/Sweefl-Player-0.3.1-arm64-debug.apk)，20,778,831 字节；[校验文件](../dist/Sweefl-Player-0.3.1-arm64-debug.sha256.txt)。

SHA-256：`4A2E153EC004158B328FA1453CF632993B3B39FADDF71FF31E571AA351F889C0`。
