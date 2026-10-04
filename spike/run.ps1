# RegainNBT feasibility spike - rebuild classpath, compile, run.
# Usage:  powershell -ExecutionPolicy Bypass -File spike\run.ps1 [-Main Spike5]
param([string]$Main = "Spike5")

$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$env:JAVA_TOOL_OPTIONS = "-Duser.language=en -Duser.country=US"

$spike = Split-Path -Parent $MyInvocation.MyCommand.Path
$libs  = Join-Path $spike "libs"
$out   = Join-Path $spike "out"
$src   = Join-Path $spike "src"

$jdk = Join-Path $env:USERPROFILE ".gradle\jdks\eclipse_adoptium-25-amd64-windows.2"
if (-not (Test-Path (Join-Path $jdk "bin\javac.exe"))) {
    Write-Output "JDK 25 not found at: $jdk"
    Write-Output "Install it via Gradle toolchain provisioning, or edit the jdk path in this script."
    exit 1
}

$mcJar = Join-Path $env:USERPROFILE ".gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-common-deobf\26.1.2\minecraft-common-deobf-26.1.2.jar"
$infoJson = Join-Path $env:USERPROFILE ".gradle\caches\fabric-loom\26.1.2\mojang_minecraft_info.json"

if (-not (Test-Path (Join-Path $libs "minecraft-common-deobf-26.1.2.jar"))) {
    Write-Output "=== rebuilding spike/libs from local Gradle caches ==="
    if (-not (Test-Path $mcJar))    { Write-Output "missing: $mcJar"; exit 1 }
    if (-not (Test-Path $infoJson)) { Write-Output "missing: $infoJson"; exit 1 }
    New-Item -ItemType Directory -Force -Path $libs | Out-Null
    Copy-Item -LiteralPath $mcJar -Destination (Join-Path $libs "minecraft-common-deobf-26.1.2.jar") -Force

    $versionJson = Get-Content $infoJson -Raw | ConvertFrom-Json
    $mods = Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1"
    $idx = @{}
    Get-ChildItem $mods -Recurse -Filter *.jar -ErrorAction SilentlyContinue | ForEach-Object {
        if (-not $idx.ContainsKey($_.Name)) { $idx[$_.Name] = New-Object System.Collections.ArrayList }
        [void]$idx[$_.Name].Add($_.FullName)
    }
    $ok = 0; $miss = @()
    foreach ($lib in $versionJson.libraries) {
        $rel = $lib.downloads.artifact.path
        if (-not $rel) { continue }
        $name = Split-Path $rel -Leaf
        if (-not $idx.ContainsKey($name)) { $miss += $name; continue }
        $pick = $idx[$name] | Where-Object { $_ -like ("*" + ($rel -replace "/", "\")) } | Select-Object -First 1
        if (-not $pick) { $pick = $idx[$name] | Select-Object -First 1 }
        Copy-Item -LiteralPath $pick -Destination (Join-Path $libs $name) -Force -ErrorAction SilentlyContinue
        $ok++
    }
    Write-Output ("  copied=" + $ok + "  missing=" + $miss.Count + " (missing entries are non-Windows natives)")
}

Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $out | Out-Null

Write-Output "=== compile ==="
$sources = Get-ChildItem $src -Filter *.java | ForEach-Object { $_.FullName }
& (Join-Path $jdk "bin\javac.exe") -nowarn -cp "$libs\*" -d $out $sources
if ($LASTEXITCODE -ne 0) { Write-Output "compile failed"; exit 1 }

Write-Output "=== run $Main ==="
& (Join-Path $jdk "bin\java.exe") -cp "$out;$libs\*" $Main
exit $LASTEXITCODE
