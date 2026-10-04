# RegainNBT T4 - offline ID rename table generator / auditor.
#
#   powershell -ExecutionPolicy Bypass -File tools\audit\generate-id-renames.ps1
#   powershell -ExecutionPolicy Bypass -File tools\audit\generate-id-renames.ps1 -SkipDownload
#   powershell -ExecutionPolicy Bypass -File tools\audit\generate-id-renames.ps1 -Strict     # exit 2 if holes remain
#
# Steps:
#   1. download the 1.20.4 item / block name lists (two independent sources; the union is committed
#      to tools/audit/data/). Also download the 1.20.2 registries to derive the "pre-1.20.3" domain.
#   2. run real 26.3 Minecraft code + vanilla DFU (References.ITEM_NAME / BLOCK_NAME) over every name
#      at 3700 -> SharedConstants.WORLD_VERSION.
#   3. write src/main/resources/regainnbt/id_renames.json plus the audit report under tools/audit/out/.
#
# Why two sources: mcmeta is a direct dump of vanilla's generated registries report, minecraft-data is an
# independent community index. Equal counts (1312 items / 1058 blocks for 1.20.4) is the sanity check;
# the script prints the set difference.
#
# Why the extra "pre-1.20.3" domain: "grass" is NOT a 1.20.4 id - it was renamed to "short_grass" in 1.20.3,
# so it is absent from the 1.20.4 dumps. But report 2.6 shows legacy commands/NBT still carry it and vanilla
# DFU does not rename it. The domain is derived automatically as (1.20.2 names) \ (1.20.4 names).
#
# NOTE: keep this file pure ASCII. Windows PowerShell 5.1 reads .ps1 as ANSI, so non-ASCII comments can turn
# into stray quote/backtick characters and break parsing. Chinese docs live in tools/audit/README.md.
param(
    [switch]$SkipDownload,
    [switch]$SkipNames,
    [switch]$Strict
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root

$cacheDir = Join-Path $root "build\audit-cache"     # build/ is gitignored
$dataDir  = Join-Path $root "tools\audit\data"      # extracted name lists (committed)
$outDir   = Join-Path $root "tools\audit\out"
New-Item -ItemType Directory -Force -Path $cacheDir, $dataDir, $outDir | Out-Null

$sources = @(
    @{ Name = "mcmeta-items";  Url = "https://raw.githubusercontent.com/misode/mcmeta/1.20.4-registries/item/data.json";               File = "mcmeta-items.json";  Kind = "items" },
    @{ Name = "mcmeta-blocks"; Url = "https://raw.githubusercontent.com/misode/mcmeta/1.20.4-registries/block/data.json";              File = "mcmeta-blocks.json"; Kind = "blocks" },
    @{ Name = "mcd-items";     Url = "https://raw.githubusercontent.com/PrismarineJS/minecraft-data/master/data/pc/1.20.3/items.json";  File = "mcd-items.json";     Kind = "items" },
    @{ Name = "mcd-blocks";    Url = "https://raw.githubusercontent.com/PrismarineJS/minecraft-data/master/data/pc/1.20.4/blocks.json"; File = "mcd-blocks.json";    Kind = "blocks" }
)
$legacySources = @(
    @{ Name = "mcmeta-items-1.20.2";  Url = "https://raw.githubusercontent.com/misode/mcmeta/1.20.2-registries/item/data.json";  File = "mcmeta-items-1.20.2.json";  Kind = "items" },
    @{ Name = "mcmeta-blocks-1.20.2"; Url = "https://raw.githubusercontent.com/misode/mcmeta/1.20.2-registries/block/data.json"; File = "mcmeta-blocks-1.20.2.json"; Kind = "blocks" }
)

function Fetch($list) {
    $ProgressPreference = "SilentlyContinue"
    foreach ($s in $list) {
        $dst = Join-Path $cacheDir $s.File
        if (Test-Path $dst) { Write-Output ("cache hit : " + $s.File); continue }
        try {
            Invoke-WebRequest -Uri $s.Url -OutFile $dst -TimeoutSec 180 -UseBasicParsing
            Write-Output ("downloaded: " + $s.File + "  (" + (Get-Item $dst).Length + " bytes)")
        } catch {
            Write-Output ("DOWNLOAD FAILED: " + $s.Url + " :: " + $_.Exception.Message)
        }
    }
}
if (-not $SkipDownload) { Fetch $sources; Fetch $legacySources }

function Get-Names([string]$file) {
    $p = Join-Path $cacheDir $file
    if (-not (Test-Path $p)) { return @() }
    $j = Get-Content $p -Raw | ConvertFrom-Json
    $names = foreach ($e in $j) { if ($e -is [string]) { $e } else { $e.name } }
    return @($names | Where-Object { $_ } | Sort-Object -Unique)
}

function Write-NameFile([string]$path, [string[]]$header, [string[]]$names) {
    # UTF-8 without BOM: PS 5.1 "Set-Content -Encoding utf8" prepends a BOM, and then the first line
    # stops being a comment for simple line-based readers.
    $enc = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllLines($path, ($header + $names), $enc)
}

$maps = @{}
$maps["items"] = @()
$maps["blocks"] = @()

if (-not $SkipNames) {
    foreach ($kind in @("items", "blocks")) {
        $sets = @{}
        foreach ($s in $sources | Where-Object { $_.Kind -eq $kind }) {
            $sets[$s.Name] = Get-Names $s.File
            Write-Output ("source " + $s.Name.PadRight(14) + " : " + $sets[$s.Name].Count + " names")
        }
        $all = @()
        foreach ($k in $sets.Keys) { $all += $sets[$k] }
        $union = @($all | Sort-Object -Unique)
        $names = @($sets.Keys | Sort-Object)
        if ($names.Count -ge 2) {
            $a = $sets[$names[0]]
            $b = $sets[$names[1]]
            $onlyA = @($a | Where-Object { $b -notcontains $_ })
            $onlyB = @($b | Where-Object { $a -notcontains $_ })
            Write-Output ("  only in " + $names[0] + " : " + $onlyA.Count + "  " + (($onlyA | Select-Object -First 10) -join ", "))
            Write-Output ("  only in " + $names[1] + " : " + $onlyB.Count + "  " + (($onlyB | Select-Object -First 10) -join ", "))
        }
        $target = Join-Path $dataDir ("names-" + $kind + "-1.20.4.txt")
        $header = @(
            "# 1.20.4 " + $kind + " name list (RegainNBT T4 audit input, generated by tools/audit/generate-id-renames.ps1)",
            "# the mod's closed input domain: 1.20.4 only",
            "# sources (union):"
        )
        foreach ($s in $sources | Where-Object { $_.Kind -eq $kind }) { $header += ("#   " + $s.Url) }
        $header += ("# " + $union.Count + " names")
        Write-NameFile $target $header $union
        Write-Output ("wrote " + $target + " : " + $union.Count + " names")
        $maps[$kind] = $union
    }

    # pre-1.20.3 domain = (1.20.2 names) minus (1.20.4 names), for both registries
    $extra = @{}
    foreach ($kind in @("items", "blocks")) {
        $ls = $legacySources | Where-Object { $_.Kind -eq $kind }
        $legacySet = @{}
        foreach ($s in $ls) { $legacySet[$s.Name] = Get-Names $s.File }
        $legacyAll = @()
        foreach ($k in $legacySet.Keys) { $legacyAll += $legacySet[$k] }
        $legacyUnion = @($legacyAll | Sort-Object -Unique)
        $diff = @($legacyUnion | Where-Object { $maps[$kind] -notcontains $_ })
        Write-Output ("1.20.2 " + $kind + " = " + $legacyUnion.Count + " ; dropped between 1.20.2 and 1.20.4 = " + $diff.Count + " : " + ($diff -join ", "))
        foreach ($n in $diff) { $extra[$n] = $true }
    }
    $extraNames = @($extra.Keys | Sort-Object)
    $extraTarget = Join-Path $dataDir "extra-legacy-names.txt"
    $extraHeader = @(
        "# pre-1.20.3 legacy names (RegainNBT T4 audit input, generated by tools/audit/generate-id-renames.ps1)",
        "# derived as (1.20.2 registries) minus (1.20.4 registries): names that vanished in 1.20.3,",
        "# i.e. legacy commands/NBT from before 1.20.3 can still carry them (report 2.6: grass).",
        "# sources:"
    )
    foreach ($s in $legacySources) { $extraHeader += ("#   " + $s.Url) }
    $extraHeader += ("# " + $extraNames.Count + " names")
    Write-NameFile $extraTarget $extraHeader $extraNames
    Write-Output ("wrote " + $extraTarget + " : " + $extraNames.Count + " names")
}

$jdk = Join-Path $env:USERPROFILE ".gradle\jdks\eclipse_adoptium-25-amd64-windows.2"
if (-not (Test-Path (Join-Path $jdk "bin\javac.exe"))) { Write-Output ("JDK 25 not found: " + $jdk); exit 1 }
$cpFile = Join-Path $root "build\devcp\main.txt"
if (-not (Test-Path $cpFile)) { Write-Output ("missing " + $cpFile + " ; run: .\gradlew.bat dumpClasspath --no-configuration-cache"); exit 1 }
$cp = (Get-Content $cpFile -Raw).Trim()

$classes = Join-Path $root "build\audit-classes"
New-Item -ItemType Directory -Force -Path $classes | Out-Null
Write-Output "=== javac tools/audit/IdRenameAudit.java"
& "$jdk\bin\javac.exe" -nowarn -encoding UTF-8 -cp $cp -d $classes (Join-Path $root "tools\audit\IdRenameAudit.java")
if ($LASTEXITCODE -ne 0) { Write-Output "COMPILE FAILED"; exit 1 }

Write-Output "=== run audit"
$auditArgs = @(
    "-Duser.language=en",
    "-Duser.country=US",
    "-cp", "$classes;$cp",
    "IdRenameAudit",
    "--items", "tools/audit/data/names-items-1.20.4.txt",
    "--blocks", "tools/audit/data/names-blocks-1.20.4.txt",
    "--extras", "tools/audit/data/extra-legacy-names.txt",
    "--manual", "tools/audit/manual-fixes.json",
    "--out", "src/main/resources/regainnbt/id_renames.json",
    "--outdir", "tools/audit/reports"
)
if ($Strict) { $auditArgs += "--strict" }
& "$jdk\bin\java.exe" @auditArgs
exit $LASTEXITCODE
