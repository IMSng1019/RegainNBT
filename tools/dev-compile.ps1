# 快速 javac 检查（不跑 Gradle，避免并发构建锁）。
# 前置：先跑一次  powers .\gradlew.bat dumpClasspath --no-configuration-cache
# 用法：  powershell -ExecutionPolicy Bypass -File tools\dev-compile.ps1 [-SourceDir src/main/java] [-Out build/devcp/classes]
param(
    [string]$SourceDir = "src/main/java",
    [string]$Out = "build/devcp/classes",
    [switch]$All
)

$ErrorActionPreference = "Continue"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root

$jdk = Join-Path $env:USERPROFILE ".gradle\jdks\eclipse_adoptium-25-amd64-windows.2"
if (-not (Test-Path (Join-Path $jdk "bin\javac.exe"))) { Write-Output "JDK 25 not found: $jdk"; exit 1 }

$cpFile = Join-Path $root "build\devcp\main.txt"
if (-not (Test-Path $cpFile)) {
    Write-Output "缺少 $cpFile —— 先执行:  .\gradlew.bat dumpClasspath --no-configuration-cache"
    exit 1
}
$cp = (Get-Content $cpFile -Raw).Trim()

$outDir = Join-Path $root $Out
Remove-Item $outDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$srcDir = Join-Path $root $SourceDir
$sources = Get-ChildItem $srcDir -Recurse -Filter *.java | ForEach-Object { $_.FullName }
if (-not $sources) { Write-Output "no sources under $srcDir"; exit 0 }

Write-Output ("=== javac " + $sources.Count + " files -> " + $outDir)
& (Join-Path $jdk "bin\javac.exe") -nowarn -encoding UTF-8 -cp $cp -d $outDir $sources
$code = $LASTEXITCODE
if ($code -eq 0) { Write-Output "COMPILE OK" } else { Write-Output "COMPILE FAILED ($code)" }
exit $code
