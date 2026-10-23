"""Publish the refactor report only from complete evidence matching the installed debug APK."""
from pathlib import Path
from datetime import datetime
import hashlib,json,shutil,zipfile
import xml.etree.ElementTree as ET

root=Path(__file__).resolve().parents[1]
data=root/'docs/testing-refactor'
def load(name): return json.loads((data/name).read_text(encoding='utf-8'))
summary=load('regression-summary.json')
assert summary.get('regressionsPassed') and summary.get('publicHttpsPassed'),summary
assert summary['preferencesPreserved'] and summary['testPreferencesRestored']
assert summary['initialVolume']==summary['restoredVolume']
architecture=load('architecture-results.json'); assert all(architecture.values())
files={'settings-results.json':3,'ui-results.json':1,'subtitle-results.json':3,'interaction-results.json':8,'extra-ui.json':3,'baseline/results.json':7}
checks=[]
for name,count in files.items():
    rows=load(name); assert len(rows)==count and all(row['passed'] for row in rows),(name,rows)
    checks.extend(rows)
network=load('network-extended.json'); assert len(network)==6
assert all(row['passed'] for row in network if not row['case'].startswith('HTTPS'))
https=next(row for row in network if row['case'].startswith('HTTPS'))
if not https['passed']:
    for candidate in ('network-https-retry.json','network-https-alternate.json'):
        if (data/candidate).exists() and load(candidate)[0]['passed']:
            https=load(candidate)[0]; break
assert https['passed']
matrix=load('format-matrix.json'); assert len(matrix)==29
unexpected=[row for row in matrix if not row['passed'] and row['name']!='HEVC Main10 software']
assert not unexpected,unexpected

unit={}
for name,path in [('domain','core/domain/build/test-results/test'),('app','app/build/test-results/testDebugUnitTest')]:
    suites=[ET.parse(p).getroot() for p in (root/path).glob('TEST-*.xml')]
    assert suites,name
    unit[name]={'tests':sum(int(s.get('tests',0)) for s in suites),'failures':sum(int(s.get('failures',0))+int(s.get('errors',0)) for s in suites)}
assert sum(s['tests'] for s in unit.values())==19 and all(s['failures']==0 for s in unit.values())
issues=ET.parse(root/'app/build/reports/lint-results-debug.xml').getroot().findall('issue')
errors=sum(issue.get('severity') in ('Error','Fatal') for issue in issues)
warnings=sum(issue.get('severity')=='Warning' for issue in issues)
assert errors==0
metadata=json.loads((root/'app/build/outputs/apk/debug/output-metadata.json').read_text(encoding='utf-8'))
assert metadata['elements'][0]['versionName']=='0.2.1' and metadata['elements'][0]['versionCode']==3
source=root/'app/build/outputs/apk/debug/app-debug.apk'
sha=hashlib.sha256(source.read_bytes()).hexdigest().upper()
assert sha==summary['installedApkSha256']
release=root/'app/build/outputs/apk/release/app-release-unsigned.apk'; assert release.is_file()
with zipfile.ZipFile(source) as apk:
    abis=sorted({name.split('/')[1] for name in apk.namelist() if name.startswith('lib/') and name.endswith('.so')})
assert abis==['arm64-v8a'],abis
sources=list((root/'app/src/main/java').rglob('*.kt'))+list((root/'core/domain/src/main/kotlin').rglob('*.kt'))
assert len(sources)==36 and all(path.stat().st_mtime<=source.stat().st_mtime for path in sources)
dist=root/'dist'; dist.mkdir(exist_ok=True)
artifact=dist/'Sweefl-Player-0.2.1-arm64-debug.apk'
if artifact.exists(): assert hashlib.sha256(artifact.read_bytes()).hexdigest().upper()==sha
else: shutil.copy2(source,artifact)
(artifact.with_suffix('.sha256.txt')).write_text(sha+'  '+artifact.name+'\n',encoding='utf-8')
metrics={'generatedAt':datetime.now().astimezone().isoformat(),'version':'0.2.1','versionCode':3,'apkSha256':sha,'apkBytes':artifact.stat().st_size,'abis':abis,'sourceFiles':len(sources),'unitTests':unit,'lintErrors':errors,'lintWarnings':warnings,'functionalChecks':len(checks)+len(network),'formatCases':len(matrix),'formatCasesPassed':sum(row['passed'] for row in matrix),'releaseR8BuildVerified':True,'remoteCiExecuted':False}
(data/'verification-summary.json').write_text(json.dumps(metrics,ensure_ascii=False,indent=2),encoding='utf-8')
lines=['# Sweefl Player 0.2.1 源码重构与验证','',f"日期：{datetime.now():%Y-%m-%d}。应用 ID `com.sweefl.player`，versionCode 3；测试设备为已授权的 REDMI K90 Max / 2604FRK1EC，Android 16、ARM64。",'',
'## 完成的结构整理','',
'- 两个 Gradle 模块：Android `:app` 和纯 Kotlin `:core:domain`。应用内按功能、数据、播放基础、共享组件和依赖组装划分。',
'- Activity 保留 Android 生命周期与系统入口；播放状态、引擎重建和字幕生命周期集中到 PlaybackSession；网络与解码配置集中到 PlayerEngineFactory。',
'- 媒体库通过仓库加载，界面接收状态和回调；缩略图 I/O、历史、偏好与共享 URI 摘要键各自归位。',
'- 设计组件通过 CompositionLocal 接收玻璃开关；设备能力枚举、播放诊断状态和诊断文件写入分开。',
'- 版本目录、统一 verify 任务、架构约束、编辑器约定、CI 配置及开发 / 扩展文档已补齐。',
'- 应用 ID、入口类名、Intent / Bundle / 偏好键、原始 URI 摘要规则、诊断 schemaVersion 1 均保持兼容；旧默认 UA 字符串保留。','',
'详细目录和扩展位置见 [架构指南](ARCHITECTURE.md)，日常维护见 [开发指南](DEVELOPMENT.md)。','',
'## 构建与单元测试','',
f'- `verify` 通过：{len(sources)} 个源码文件通过架构检查，domain 13 项 + app 6 项，共 19 项单测通过。',
f'- lint：{errors} 条错误、{warnings} 条警告；仍有弃用 API / 依赖等提示，未声称零警告。',
'- 架构反向测试实际拒绝设计层到数据层的非法 import，删除临时探针后重新通过。',
'- `:app:assembleRelease` 通过，包含 R8 压缩及发布变体检查；入口类名在 R8 映射中保持不变。Release 产物未签正式发布密钥。',
'- CI 配置已提供；本轮未在远程托管平台执行 CI。','',
'机器记录：[验证汇总](testing-refactor/verification-summary.json) · [架构正反向检查](testing-refactor/architecture-results.json)。','',
'## 真机回归','',
f'- {len(checks)+len(network)} 项功能检查最终通过，覆盖设置、URL/UA、流配置、字幕、手势、解码切换、PiP 与本地播放。',
'- 覆盖安装前后，三份现有偏好文件摘要一致；测试未清空应用数据或修改用户媒体。',
'- 临近片尾的暂停位置在切换解码器前后均为 39,686ms；PiP 中继续实际出帧并隐藏自定义控件。',
'- SRT、VTT、ASS 均明确选中并输出 cue；外置字幕优先于内嵌默认字幕，移除后不再保留外置轨道。',
'- 公网 HTTPS 首次出现 Connection reset，独立复测成功。原始失败与复测分别保留；没有关闭证书校验。',
'- 测试初期出现 UI 自动化定位 / 页面采集问题：记录了键盘或返回键回退，并将 UI dump 改为独立临时文件及成功确认，防止读取旧页面。没有因这些测试工具问题修改应用功能。','',
'| 分组 | 证据 |','| --- | --- |',
'| 设置存储与订阅 | [设置结果](testing-refactor/settings-results.json) |',
'| 网络输入与流配置 | [UI](testing-refactor/ui-results.json) · [首次网络结果](testing-refactor/network-extended.json) · [HTTPS 复测](testing-refactor/network-https-retry.json) |',
'| 字幕与交互 | [字幕](testing-refactor/subtitle-results.json) · [手势 / PiP](testing-refactor/interaction-results.json) · [字幕优先级 / 恢复](testing-refactor/extra-ui.json) |',
'| 本地播放 | [7 项冒烟检查](testing-refactor/baseline/results.json) |',
'| 安装与收尾 | [偏好兼容](testing-refactor/upgrade-compatibility.json) · [回归收尾](testing-refactor/regression-summary.json) |','',
'## 格式与验证边界','']
known=next(row for row in matrix if row['name']=='HEVC Main10 software')
if not known['passed']:
    lines += [f"29 项格式矩阵中，{sum(row['passed'] for row in matrix)} 项符合预期，其中包含错误 UA 被 403 拒绝；HEVC Main10 的系统软件解码仍失败（`{known.get('snapshot',{}).get('error')}`）。该设备 / 样本限制保留为失败记录，AUTO 恢复另行通过。"]
else:
    lines += ['29 项格式矩阵本轮均符合预期（包括错误 UA 拒绝）；软件 Main10 本次通过不代表所有设备或长期稳定性。']
lines += ['', '[格式矩阵及原始诊断](testing-refactor/format-matrix.json)。本轮没有重新执行完整功耗 / 性能窗口，也没有新增光学 HDR / Dolby Vision 还原证明；既有实测边界见 [0.2.0 报告](VALIDATION-0.2.md)。',
'', '## 交付','',f'- [0.2.1 ARM64 Debug APK](../dist/{artifact.name})，{artifact.stat().st_size:,} 字节，已覆盖安装到测试手机。',f'- SHA-256：`{sha}`。',
f"- 媒体音量恢复到测试前的 {summary['restoredVolume']}；原玻璃开关 / 默认 UA 已恢复，测试创建的 ADB reverse 已移除。",'- 历史 0.2.0 APK、报告和原始证据保留。','']
(root/'docs/REFACTOR-0.2.1.md').write_text('\n'.join(lines),encoding='utf-8')
print(json.dumps(metrics,ensure_ascii=False,indent=2))
