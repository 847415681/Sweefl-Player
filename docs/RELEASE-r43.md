# r43 交付与验证快照

本页记录 2026-10-25 已完成的功能交付，供下一位 agent 追溯。当前任务已经结束；未验证项不是自动授权的新任务。日常接手以[交接入口](AGENT-HANDOFF.md)和最新 Git 状态为准。

## 基线和交付物

- 应用：0.3.2，versionCode 6，交付标识 r43；只发布 arm64-v8a，minSdk 26 / targetSdk 35。
- 功能提交：[862c8951ed148042884a628e1196e932bc38494b](https://github.com/847415681/Sweefl-Player/commit/862c8951ed148042884a628e1196e932bc38494b)，已推送 main。
- 对应 [GitHub CI](https://github.com/847415681/Sweefl-Player/actions/runs/40195752025) 已完成且成功。后续文档提交不改变该功能包对应关系；新代码必须取得自己的验证结果。
- 本地完整检查：`verify :app:assembleRelease` 通过，包含 90 个 Kotlin 文件的架构检查、app/domain 单测、Debug、lint 和 R8 Release。
- 原生来源套件：4 个归档、88 项清单通过；本轮没有更改修正后的 Nextlib AAR。

| 本机交付物 | SHA-256 |
| --- | --- |
| [APK](../dist/Sweefl-Player-0.3.2-r43.apk) | `b4542085a9b03a1d631d7dbbe1a1ddcc94c713497fe16531b56fae35784eea4d` |
| [完整源码归档](../dist/Sweefl-Player-0.3.2-r43-source.zip) | `6bdee883c47bf95bc2eb2814d841c895347fd8d4ad22ee91c08265303572f0b1` |
| [原生 AAR](../app/libs/nextlib-media3ext-1.8.0-0.9.0-sweefl.aar) | `bcd338efafe7186f20a75885a06b0a3d9c3d7a5f459997628820ffd300dde97e` |

APK 是 Release 构建，沿用既有开发证书，v2/v3 签名与 ZIP 对齐已验证；不是正式商店签名或全设备 16KB 页兼容证明。签名证书 SHA-256 为 `c90082a672d3b119b20a55a71211bd3b8f19289f5732944b3bb45fec17e35050`。源码归档包含 324 项文件校验清单，已逐项核验，排除了设备证据、缓存和签名材料。

[交付清单](../dist/Sweefl-Player-0.3.2-r43-delivery.json)及上述 dist 文件仅在原工作区存在，不随 Git 上传。r43 源码归档是功能交付时快照，不包含随后新增的交接整理；最新说明在 Git。不得为了更新文档覆盖已交付 APK/源码归档。

## 功能与必须保留的行为

- B 站 BV/av、分 P、b23、ep/ss、直播间及分享文本解析；按实际流目录显示选集、清晰度、音轨。
- 视频和独立音频合并播放；画质/音轨切换保留内容位置和播放意图；没有独立音频目录时仍使用 Media3 原生轨道能力。
- 服务器 DVR：可拖动回看、后退十秒、返回直播；暂停冻结内容，换清晰度保留回看位置。
- 只持久化稳定页面身份；首次分享别名解析后恢复 canonical 历史/配置，明确保存的位置优先。
- 失败选择与生命周期待续请求分开；旧引擎自动恢复不能抢占正在进行的新选择。
- VOD/PGC 使用 Desktop UA 与根 Referer，并优先选择 API 提供的非 P2P 地址；Live 使用独立 Mobile UA/直播间 Referer。Cookie 仅用于精确 HTTPS API 主机。
- r41 原生 Surface 生命周期与玻璃就绪规则、r42 帧调度/去重/工作线程位图所有权保持。

实现地图见[架构指南](ARCHITECTURE.md)，协议细节见 [Bilibili 说明](BILIBILI.md)。

## 真实验证与证据索引

设备为 Redmi K20 Pro / raphael，Android 15/API35，arm64。以下原始证据只在原工作区的忽略目录中，克隆仓库不会获得这些文件；不能因克隆后缺失就把已记录结果当成新一次执行。

| 范围 | 结果与本地证据位置 |
| --- | --- |
| 普通选轨 | 最终 Debug 的 HLS/DASH 180p/360p/自动、英语/中文音轨、暂停/播放位置、单流控件隐藏与旧字幕入口通过；`docs/testing-r43-online/attempt15-final-network-tracks/` |
| 分 P | P1/P2 解码、暂停选集与拖动、恢复帧/内容增长通过；`attempt12-vod-complete/`。该轮后续新增选轨用例因初始画质假设错误而失败，不应标记整轮全部通过 |
| B 站画质/音轨 | 按实际目录完成 q112→低分辨率→q112、音轨 ID/解码格式变化及另一轨道/位置/意图保持；`attempt13-vod-actual-choices/` |
| 分享历史、生命周期、登录 | 重复无 `?p` 分享链接恢复已保存位置；暂停回看的旋转和 HOME/原任务恢复；官方登录返回保留输入、未进 PiP；`attempt14-lifecycle-canonical/` |
| room6 DVR | 一小时范围、六秒拖动且 UP 前确认中点、约半小时回看解码、换画质、至少暂停 12 秒后继续、返回直播与暂停倒退十秒通过；`attempt10-live-observed-slider/` |
| 旧功能 | 横屏键盘、字幕同步/区间/重叠/恢复、原生/GPU/FFmpeg 跳转代次检查通过；`attempt09-core-regression/` |
| 真实加载 | 限速及 seek 延迟夹具触发 STATE_BUFFERING，加载指示及玻璃保留；`attempt11-delayed-loading/` |
| Release 通用播放 | MP4/HLS/DASH/FFmpeg 播放暂停通过；`attempt16-release/`。新 B 站工装的单次隐藏 HUD 断言失败，保留原记录 |
| Release B 站 | 等待实际自动隐藏后检查非黑且运动的视频 ROI、正常暂停、选集/清晰度/返回直播入口通过；`attempt17-release-bilibili/`，没有读取 Debug 诊断 |

精确本机目录可用 `Get-ChildItem docs/testing-r43-online -Directory` 查看。失败与复测分开保存。入口、环境变量和适用构建见[工具说明](../tools/README.md)。不要重放失败的坐标估算或把 API 成功等同于解码成功。

## 收尾状态与数据保护

17 轮共 34 份安装/恢复主回执，三份偏好安装保留 51/51、恢复 51/51；4 份单独旋转回执均成功，guard 也逐轮执行旋转与音量回读断言。音量基线在不同轮次有变化，必须恢复每次新捕获的值，不能硬编码旧值。17 份回执记录删除自建 reverse，Lead 最后另行读取列表确认 tcp:8765 不存在。

汇总位于本机忽略的 `docs/testing-r43-online/restoration-summary.json`。测试夹具服务已停止；功能交付时 Release 已留在手机，没有待续的构建、设备测试或 CI 作业。下一位 agent 如需设备操作，重新核对目标并建立当次备份，不依赖旧授权环境快照推断当前连接状态。

## 未验证或明确不支持

- 真实账号登录后的付费权益，以及 B 站回看进程被系统终止后的重建没有真机验证；旋转/HOME 验证发生在同一进程、原任务中。
- 音轨通过实际解码器/输入格式验证，未做 PCM 或听音测量。
- 没有 PDT 的直播时间映射不承诺逐帧墙钟精度；房间、账号和时段的可用目录/回看范围可能改变。
- 多段 durl 暂不拼接。单台设备结果不覆盖所有编码、HDR、DRM、地区、设备与长期功耗。
- 正式签名、完整无障碍、多设备覆盖仍属后续范围。只有用户提出相应任务后再推进。
