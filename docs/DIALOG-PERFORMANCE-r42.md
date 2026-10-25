# r42：视频大弹窗玻璃流畅度优化

日期：2026-10-25。应用版本仍为 0.3.2 / versionCode 6；r42 是交付标识。对照设备为 Redmi K20 Pro / Android 15，比较相同 Release 构建类型、竖屏属性窗口、同一 1080p/30fps 合成片源及相同模糊设置。性能采样期间不录屏；视觉录像独立执行。

## 原因与实现

原弹窗每次完成窗口复制和软件合成后再等 67ms，并非稳定 15fps；实际只绘制约 9–10 次/秒。它还要搬运大张位图，并对整个大弹窗执行全尺寸 GPU 折射和模糊。

[DialogBackdrop](../app/src/main/java/com/sweefl/player/core/designsystem/DialogBackdrop.kt)改为显示帧对齐、按开始时间推进的刷新期限，遵循刷新档位，保持单次在途、不堆积补帧。下层 UI 绘制代次、视频帧对象、几何、遮罩和采样比例均不变时跳过复制，避免暂停后增加负担。

[LiquidGlass](../app/src/main/java/com/sweefl/player/core/designsystem/LiquidGlass.kt)仅对大弹窗的模糊背景传输层优化：物理模糊半径达到 4px 后，捕获与离屏层的宽高各减半，像素量约为四分之一；Gaussian 半径同步缩放，保持屏幕上的模糊范围和折射坐标。前景文字、锐利边缘仍按完整分辨率绘制；零模糊与很小模糊保留原分辨率。播放 HUD 的非合成传输层不降分辨率。

保留完整的 Window UI + 视频合成和主线程的自有背景快照，不把包含动态玻璃/跳转覆盖的 Window 图像误当静态文字缓存，也不在工作线程读取会被上层循环复用的视频位图。首帧就绪门控、模糊支撑区域只增不减、关闭玻璃可见性和 loading 交接行为保留。

## 实测

以下是大弹窗 Window 的 UI 绘制数据，不等同于视频解码帧率，也不是所有设备上的帧率承诺。

| 模糊 | r41 绘制/秒 | r42 绘制/秒 | r41 → r42 P95 帧耗时 | r41 → r42 超时帧 |
| --- | ---: | ---: | --- | --- |
| 0% | 9.92 | 26.26 | 13 → 17ms | 0 → 3.47% |
| 12% | 9.44 | 39.77 | 21 → 15ms | 7.02 → 1.88% |
| 50% | 9.39 | 39.03 | 17 → 15ms | 1.46 → 1.27% |
| 100% | 未采集 | 37.03 | r42：18ms | r42：5.59% |

12% 时弹窗 GPU P95 从 14ms 降到 7ms。底层主窗口在两版都约 31 次/秒；其 P95 同为 21ms，超时帧 4.43% 与 5.60%。零模糊保留全尺寸，因此更新更频繁后单帧 P95 略有上升；最大模糊仍有设备负载限制。

首轮仅优化捕获调度和位图尺寸，虽然达到 34.89 次/秒，但弹窗超时帧达 29.69%，没有采用为最终交付。当前结果来自随后加入 GPU 离屏层优化的构建，失败与中间数据保留在本机。

独立录像在第 2–10 秒测量弹窗内部固定区域的画面变化，以 0.5/255 的平均 RGB 差异阈值估计：属性窗口从 8.38 增至 21.75 次/秒，手势窗口从 8.88 增至 20.75 次/秒。0.25、0.5、1.0 三个阈值均显示改善。该估计受压缩、运动幅度和录屏采样限制，用于证明实际背景变化增加，不将 40 次 UI 绘制宣称为 40 个不同视频帧。

## 验证与复现

- `verify` 与 `:app:assembleRelease` 通过；最终构建耗时 1 分 51 秒，包含架构、单测、lint 和 Release 优化；最终审查补齐了暂停状态模糊支撑区域增大时的裁切失效通知，独立代码复查未发现新的可操作问题。
- 属性初现、模糊拖动、手势初现录像共 1304 帧，其中可见帧分别 406、445、418；有效覆盖成立，未检出中性材料候选。人工检查两种窗口的第一可见帧，已有折射/模糊而非先显示普通透明。
- 开启、关闭、关闭后重入、再次开启：16 项实际图标像素检查及暂停/继续通过。
- 属性窗口到画面设置窗口再返回、关闭后旋转重开保持相同背景主色及暂停位置 0:07；稳定暂停状态 3 秒采样中两个窗口合计 0 次重绘；暂停后模糊切到 100%、0%、再到 100% 均保持正确背景。该子设置流程是替换窗口，不是同时堆叠两层弹窗的测试。
- 同轮 GPU 候选的两档模糊重复跳转录像 198 + 205 帧，覆盖有效，没有错误主色或中性底色候选。进出及六次比例切换录像 777 帧，按真实 UI 边界分析得到 522 个有效彩色可见帧，没有错误主色或中性候选。该录像为竖屏：仅检验位于视频区域的中央三个控件，FIT 模式上下黑边处的玻璃不要求呈现视频主色。
- 测试均通过保护脚本，恢复用户历史、配置、偏好、音量及旋转；不删除用户媒体。

[逐窗口分析工具](../tools/analyze_dialog_performance.py)从系统 uptime 与每个 Window 的 stats-since 计算采样时长，避免把后续截图耗时当作测量时长。[录像变化工具](../tools/analyze_modal_motion.py)独立检查画面变化。[弹窗场景回归](../tools/dialog_scene_check.py)使用实际 UI，不读取 Release 不提供的 Debug 诊断。

```powershell
$env:SWEEFL_SERIAL='已授权设备序列号'
$env:SWEEFL_SKIP_INSTALL='1' # 先确认手机确实安装了待测版本
$env:SWEEFL_TEST_BLUR='12'
$env:SWEEFL_TEST_OUT='docs/testing-dialog-performance/new-run'
$env:SWEEFL_UI_SUITES='dialog-perf,modal-material,dialog-scene,control-visibility'
python tools/guarded_v032.py
python tools/analyze_dialog_performance.py docs/testing-dialog-performance/new-run
```

依赖同原测试：自有本地夹具服务、已授权设备及 Python 图像分析依赖。原始转储、偏好备份和录像不提交到 GitHub。完整工具说明见[开发指南](DEVELOPMENT.md)和[交接文档](AGENT-HANDOFF.md)。

## 同步修正 GitHub Actions

用户提供的实际失败是 `sdkmanager tools` 找不到废弃的 `tools` 包。[工作流](../.github/workflows/android.yml)为 `setup-android@v3` 显式设置 `packages: 'platform-tools'`，保留后续固定 SDK 35 / Build Tools 35.0.0 的安装。上游 v3 默认值为 `tools platform-tools`；本次只覆盖该输入，不以 Node.js 24 的报错栈猜测根因，不顺带升级其他 Actions。远端执行状态以本次提交的 Actions 结果为准。

交付继续使用同一本地开发证书以兼容覆盖安装，不包含签名私钥。正常原生 HDR 输出不改；过渡覆盖及装饰玻璃仍是 SDR，不声称 HDR 过渡保真。
