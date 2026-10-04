# 编译并运行一个 scratch 验证程序（用真实 26.3 Minecraft 代码 + 项目 classpath）。
# 用法： powershell -ExecutionPolicy Bypass -File tools\dev-run.ps1 -Source scratch\foo\Probe.java
param(
    [Parameter(Mandatory = $true)][string]$Source,
    [string]$Main = "",
    [string]$Arguments = ""
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$jdk = Join-Path $env:USERPROFILE ".gradle\jdks\eclipse_adoptium-25-amd64-windows.2"
$cpFile = Join-Path $root "build\devcp\main.txt"
if (-not (Test-Path $cpFile)) { Write-Output "missing $cpFile; run: .\gradlew.bat dumpClasspath --no-configuration-cache"; exit 1 }
$cp = (Get-Content $cpFile -Raw).Trim()
$srcFile = Join-Path $root $Source
if (-not (Test-Path $srcFile)) { Write-Output "missing source: $srcFile"; exit 1 }
$outDir = Join-Path $root ("build\scratch\" + [IO.Path]::GetFileNameWithoutExtension($srcFile))
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$projClasses = Join-Path $root "build\classes\java\main"
$projRes = Join-Path $root "build\resources\main"
$extra = "$projClasses;$projRes"
& "$jdk\bin\javac.exe" -nowarn -encoding UTF-8 -cp "$cp;$extra" -d $outDir $srcFile
if ($LASTEXITCODE -ne 0) { Write-Output "javac failed"; exit 1 }
if (-not $Main) { $Main = [IO.Path]::GetFileNameWithoutExtension($srcFile) }
& "$jdk\bin\java.exe" "-Duser.language=en" "-Duser.country=US" -cp "$outDir;$extra;$cp" $Main $Arguments
exit $LASTEXITCODE
