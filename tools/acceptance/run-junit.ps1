# Headless JUnit 5 runner (T7). Compiles src/main/java + src/test/java with the exported
# Gradle classpath (build/devcp/test.txt) and runs JUnit via scratch/tests-verify/TestRunner.java.
# Never touches Gradle, so it cannot fight the build lock.
#
# The classpath is ~45k chars, which exceeds the Windows command line limit, so javac/java are
# invoked through @argfiles.
#
# Prereq: run once  .\gradlew.bat dumpClasspath --no-configuration-cache
# Usage:  powershell -ExecutionPolicy Bypass -File tools\acceptance\run-junit.ps1
#         powershell -ExecutionPolicy Bypass -File tools\acceptance\run-junit.ps1 -Select regainnbt.translate.DfuGoldenMatrixTest
#         powershell -ExecutionPolicy Bypass -File tools\acceptance\run-junit.ps1 -SkipMainCompile   # when src/main is mid-edit
param(
    [string[]]$Select = @("regainnbt"),
    [switch]$SkipCompile,
    [switch]$SkipMainCompile
)
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
$jdk = Join-Path $env:USERPROFILE ".gradle\jdks\eclipse_adoptium-25-amd64-windows.2"
if (-not (Test-Path (Join-Path $jdk "bin\javac.exe"))) { Write-Output "JDK 25 not found: $jdk"; exit 1 }

$cpFile = Join-Path $root "build\devcp\test.txt"
if (-not (Test-Path $cpFile)) {
    Write-Output "missing $cpFile -- run: .\gradlew.bat dumpClasspath --no-configuration-cache"
    exit 1
}
# powershell -File passes "-Select a,b" as one string; split so both forms work.
$Select = @($Select | ForEach-Object { $_ -split "," } | Where-Object { $_ })

$testcp = (Get-Content $cpFile -Raw).Trim()
# @argfiles are whitespace-separated, so entries with spaces would need quoting; assert we have none.
if ($testcp -match ';[^;]* ') { Write-Output "WARNING: classpath entry contains a space; @argfile may mis-parse it" }
$maincp = (Get-Content (Join-Path $root "build\devcp\main.txt") -Raw).Trim()

$workDir = Join-Path $root "build\acceptance"
New-Item -ItemType Directory -Force -Path $workDir | Out-Null
$testOut = Join-Path $workDir "test-classes"
# -SkipMainCompile reuses the last successful compile (build/acceptance/main-classes), so tests can
# still run while another agent's src/main/java is momentarily mid-edit; falls back to Gradle output.
$mainOut = Join-Path $workDir "main-classes"
if ($SkipMainCompile -and -not (Test-Path $mainOut)) { $mainOut = Join-Path $root "build\classes\java\main" }
$resDir = Join-Path $root "src\main\resources"

if (-not $SkipCompile) {
    Remove-Item $testOut -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $testOut | Out-Null

    if (-not $SkipMainCompile) {
        Remove-Item $mainOut -Recurse -Force -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path $mainOut | Out-Null
        $mainSources = @(Get-ChildItem (Join-Path $root "src\main\java") -Recurse -Filter *.java | ForEach-Object { $_.FullName })
        Write-Output ("=== javac main (" + $mainSources.Count + " files) ===")
        $mainArgs = Join-Path $workDir "javac-main.args"
        (@("-nowarn", "-encoding", "UTF-8", "-cp", $maincp, "-d", $mainOut) + $mainSources) |
            Set-Content -Path $mainArgs -Encoding ASCII
        & "$jdk\bin\javac.exe" ("@" + $mainArgs)
        if ($LASTEXITCODE -ne 0) { Write-Output "COMPILE FAILED (main)"; exit 2 }
    } else {
        Write-Output ("=== using prebuilt main classes: " + $mainOut)
    }

    $testSources = @(Get-ChildItem (Join-Path $root "src\test\java") -Recurse -Filter *.java | ForEach-Object { $_.FullName })
    $testSources += (Join-Path $root "scratch\tests-verify\TestRunner.java")
    Write-Output ("=== javac test (" + $testSources.Count + " files) ===")
    $testArgs = Join-Path $workDir "javac-test.args"
    $testCp = $mainOut + ";" + $resDir + ";" + $testcp
    (@("-nowarn", "-encoding", "UTF-8", "-cp", $testCp, "-d", $testOut) + $testSources) |
        Set-Content -Path $testArgs -Encoding ASCII
    & "$jdk\bin\javac.exe" ("@" + $testArgs)
    if ($LASTEXITCODE -ne 0) { Write-Output "COMPILE FAILED (test)"; exit 2 }
}

Write-Output ("=== run JUnit: " + ($Select -join ", "))
$runArgs = Join-Path $workDir "java-run.args"
$runCp = $testOut + ";" + $mainOut + ";" + $resDir + ";" + $testcp
@("-Duser.language=en", "-Duser.country=US", "-cp", $runCp) | Set-Content -Path $runArgs -Encoding ASCII
& "$jdk\bin\java.exe" ("@" + $runArgs) TestRunner @Select
exit $LASTEXITCODE