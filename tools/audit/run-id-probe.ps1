# T4 verification runner: compile regainnbt/ids + the probe against the real 26.3 classpath and run it.
#   powershell -ExecutionPolicy Bypass -File tools\audit\run-id-probe.ps1
# (dev-run.ps1 needs build/classes/java/main, which only a Gradle build refreshes; this one compiles the
#  handful of sources the probe needs so the check can run without touching Gradle.)
param(
    [string]$Probe = "scratch\id-renames\Probe2.java"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root

$jdk = Join-Path $env:USERPROFILE ".gradle\jdks\eclipse_adoptium-25-amd64-windows.2"
$cpFile = Join-Path $root "build\devcp\main.txt"
if (-not (Test-Path $cpFile)) { Write-Output ("missing " + $cpFile + " ; run: .\gradlew.bat dumpClasspath --no-configuration-cache"); exit 1 }
$cp = (Get-Content $cpFile -Raw).Trim()

$out = Join-Path $root "build\scratch\id-renames-probe"
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $out | Out-Null

$sources = @(
    "src\main\java\regainnbt\core\PathKind.java",
    "src\main\java\regainnbt\core\TranslationReport.java",
    "src\main\java\regainnbt\ids\IdRenames.java",
    $Probe
)
Write-Output "=== javac (ids + probe)"
& "$jdk\bin\javac.exe" -nowarn -encoding UTF-8 -cp $cp -d $out ($sources | ForEach-Object { Join-Path $root $_ })
if ($LASTEXITCODE -ne 0) { Write-Output "COMPILE FAILED"; exit 1 }

$main = [IO.Path]::GetFileNameWithoutExtension($Probe)
Write-Output "=== java $main"
& "$jdk\bin\java.exe" "-Duser.language=en" "-Duser.country=US" -cp "$out;$(Join-Path $root 'src\main\resources');$cp" $main
exit $LASTEXITCODE
