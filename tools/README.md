# 开发验证工具

源码、单元测试和 lint 的标准入口是根项目的 `verify` Gradle 任务。此目录用于需要真实 Android 服务 / 媒体硬件的补充验证。

## r42 大弹窗验证

通过保护脚本选择 `dialog-perf,dialog-scene,modal-material,control-visibility`。性能采样不录屏；[逐窗口统计](analyze_dialog_performance.py)与[画面运动分析](analyze_modal_motion.py)分开使用，不把重复 UI 绘制当成视频帧率。模糊值通过 `SWEEFL_TEST_BLUR` 控制，保持同设备、同方向、同片源比较。

[弹窗录像](modal_material_check.py)先用正常输入暂停并定位控件，再立即恢复播放并打开弹窗，避免 Release 自动隐藏；手势窗口使用新的运动片源。[进出和比例分析](analyze_entry_material.py)读取真实 UI 按钮边界，支持竖屏；竖屏 FIT 模式的上下黑边属于正常视频布局，不能当作玻璃掉色。新分析脚本所需 RGB 必须按原生帧解码至 780×360，不使用帧率转换。

## 设备与证据配置

[公共设备助手](device_lab.py)集中 ADB、设备序列号、输出目录、启动参数和诊断读取。旧脚本从格式矩阵导入的辅助函数仍兼容；新脚本直接引用公共助手。

```powershell
$env:SWEEFL_ADB = '<Android SDK>\platform-tools\adb.exe'
$env:SWEEFL_SERIAL = '<明确授权的设备序列号>'
$env:SWEEFL_TEST_OUT = 'docs/testing-refactor'
```

未指定时，保留原测试设备序列号和历史输出路径，便于旧脚本兼容；新验证应显式设置设备和版本目录。调用前确认 `adb devices -l`。自动化不能选择列表中的任意第一台设备。

当前场景脚本使用已授权的 root 输入与 app-private 诊断读取，部分手势坐标针对 1280×2772 的测试设备。迁移到其他手机时，应调整定位和素材准备；应用本身运行不要求 root。

## 当前重构回归

先准备来自[素材研究记录](../docs/research/codec-hdr-power-research.md)的独立测试素材、本地 `Sweefl-UI-Test-01.mp4` 及 Downloads 中的 `Sweefl-test.srt/vtt/ass`。不要指向用户媒体。启动仅监听本机的[素材服务](fixture_server.py)：

```powershell
python tools/fixture_server.py --root '<fixture-directory>' --log docs/testing-refactor/http-requests.jsonl
```

另一个终端执行[回归入口](refactor_regression.py)：

```powershell
python tools/refactor_regression.py --uri content://media/external/video/media/529 --out docs/testing-refactor
```

媒体 ID 须按实际准备的测试文件调整；脚本验证其文件名。它校验覆盖安装前后的偏好文件摘要和已安装 APK 摘要，再执行设置、网络输入、流配置、字幕、交互、格式与本地播放检查。退出时恢复原媒体音量和本轮使用的偏好键，移除自己创建的 ADB reverse，不清空应用数据。

同一 APK 的中断验证可用 `--resume` 复用已经通过的分组；已安装 APK、当前构建和既有记录的 SHA-256 必须一致。失败记录与公网 HTTPS 单项复测分开保存。公网可达性失败独立报告，不能被解释为受控的本地测试全部失败，也不能改成“通过”。

主要场景：

| 工具 | 范围 |
| --- | --- |
| [设置回归](settings_ui_test.py) | 设置 → 存储 → UI 更新、重新打开和原值恢复；记录备用输入方式 |
| [网络扩展](network_extended_test.py) | 无扩展名 HLS/DASH、记住 UA / MIME、重定向、HTTPS；支持 `--only` 和独立输出 |
| [字幕回归](subtitle_ui_test.py) | SAF 选择、明确选中、实际 cue 输出 |
| [交互回归](interaction_test.py) | 手势、锁定、临近片尾切换解码器、PiP、横屏折射 |
| [格式矩阵](codec_matrix.py) | 容器 / 编码 / 软件解码与错误 UA；`--only` 选择子集 |
| [本地冒烟](device_smoke.py) | 媒体库、播放、继续播放等基础操作；显式 `--adb --serial --uri --out` |
| [证据脱敏](sanitize_ui_evidence.py) | 依据 `SWEEFL_TEST_OUT` 清理系统选择器等无关文字 |

[输入基线对照记录](../docs/testing-refactor/input-baseline-comparison.json)保留本轮旧包 / 新包排障结果。日常回归使用独立任务栈启动每个用例，并确认每次 UI dump 成功、读取本次独立文件，避免残留选择器或旧 XML 干扰定位。

## 0.3.0 玻璃与交互回归

[手势回归](glass_gesture_regression.py)覆盖三区动作、持久化、横滑提交/取消、关闭预览及 MP4/HLS/DASH 实际帧。[预览扩展](glass_preview_lifecycle.py)覆盖本地源、稀疏帧回退、A→B→A、后台释放、锁定、无视频轨道和 HDR 主播放。[弹窗检查](glass_dialog_checks.py)检查输入、键盘、横竖屏与嵌套菜单；[横屏键盘检查](glass_keyboard_landscape.py)明确复现最小高度场景，并在 `finally` 中恢复原旋转设置。

运行前使用授权测试手机、当前 Debug 包、8765 素材服务及 reverse；将本轮原始 `player_preferences` 在仓库外备份为工作区 `.android-tools/tmp/sweefl-v030-user-settings.xml`。主回归和预览扩展共享 `preserve_device_state`，无论断言是否通过均恢复该备份和媒体音量。此备份应对应本轮测试前状态，不能复用另一轮用户已更改设置之前的旧备份。完整编排还应为旧交互脚本提供 `finally` 恢复保护，并只清理自己创建的 reverse。

每组设置独立的 `SWEEFL_TEST_OUT`，例如 `docs/testing-v030/release-check-gestures`；不要覆盖失败组。固定坐标交互先检查实际方向。复合 Android root 命令必须整体 shell 引用，不能把分号后的第二次点击留在普通 shell；双击间隔须满足系统最小阈值。自行注入 DOWN/MOVE 后，测试结束要发送 CANCEL，避免残留触摸影响下一组。

本地扩展会创建 UUID 命名的自有 MP4，核对绝对路径后清理，并刷新、验证媒体索引。既有 529 测试 URI 的稀疏帧视频保留。版本结果、排障与边界见 [0.3.0 验证报告](../docs/VALIDATION-0.3.0.md)。

## 0.3.1 视觉回归

[视觉回归编排](glass_refinement_validation.py)复用弹窗、旧交互、手势与横屏键盘四组检查。开始前确认授权设备与本地 8765 素材服务；编排会安装当前 Debug 构建，并在工作区外以唯一目录即时备份三份偏好文件，结束后全部恢复并核对，同时恢复媒体音量、移除本轮创建的 reverse。设置 `SWEEFL_TEST_OUT` 指定新的证据目录；`SWEEFL_UI_SUITES=gestures,keyboard` 可仅复测指定分组。它不启动/停止主机素材服务，需要由调用方管理。

独立手势检查支持 `SWEEFL_PREFERENCES_BACKUP` 指定本轮备份；选择动作后会等待返回设置页，再检查异步保存值。对照图、首次失败与同包复测见 [0.3.1 设计与验证](../docs/GLASS-REFINEMENT-0.3.1.md)。

## 架构与历史工具

[架构反向检查](architecture_guard_test.py)用于当前 Windows 隔离工具环境；生成一个越层 import、验证构建拒绝、删除探针，再验证干净源码通过。运行时不要并发编译或打包该源码树。

[性能实验](performance_lab.py)用于专门的性能窗口，需控制素材、控件、亮度和音量，不能把 USB 充电下的电池电流当成应用功耗。

[旧报告生成器](report_results.py)与[旧设备收尾脚本](finish_device_testing.py)保留 0.2.0 的特定参数；不作为当前版本的通用入口。旧报告生成器会拒绝混用其他版本的构建结果。新的结果写入独立目录，并更新对应版本的报告。
