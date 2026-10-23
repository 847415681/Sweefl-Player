param([string[]]$Tasks = @('verify'))
$ErrorActionPreference = 'Stop'
$tools = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\.android-tools'))
$gradle = Join-Path $tools 'gradle-8.13\bin\gradle.bat'
if (!(Test-Path $gradle)) { throw 'Workspace toolchain not found. Install JDK 17 / SDK 35 and use gradlew.bat as described in README.md.' }
$env:JAVA_HOME = (Get-ChildItem (Join-Path $tools 'jdk') -Directory | Select-Object -First 1).FullName
$env:ANDROID_HOME = Join-Path $tools 'sdk'
$env:ANDROID_USER_HOME = Join-Path $tools 'android-user'
$env:GRADLE_USER_HOME = Join-Path $tools 'gradle-cache'
$env:TEMP = Join-Path $tools 'tmp'
$env:TMP = $env:TEMP
$javaHome = Join-Path $tools 'java-home'
New-Item -ItemType Directory -Force -Path $env:TEMP, $javaHome | Out-Null
$env:JAVA_TOOL_OPTIONS = "-Djava.io.tmpdir=$($env:TEMP.Replace('\','/')) -Duser.home=$($javaHome.Replace('\','/'))"
$proxyArgs = @()
$proxy = Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' -ErrorAction SilentlyContinue
if ($proxy.ProxyEnable -eq 1 -and $proxy.ProxyServer -match '^([^:;=]+):(\d+)$') {
    $proxyArgs = @("-Dhttps.proxyHost=$($Matches[1])", "-Dhttps.proxyPort=$($Matches[2])", "-Dhttp.proxyHost=$($Matches[1])", "-Dhttp.proxyPort=$($Matches[2])")
}
& $gradle -p $PSScriptRoot @proxyArgs --no-daemon --console=plain @Tasks
exit $LASTEXITCODE
