# RegainNBT end-to-end acceptance test (T7).
#
# What it does:
#   1. downloads the Fabric server launcher for MC 26.3 (fabric-loader 0.19.5, latest installer)
#   2. builds run-server/ with eula.txt + server.properties (flat world, command blocks on)
#   3. copies build/libs/regainnbt-*.jar + the matching fabric-api 26.3 jar into run-server/mods/
#   4. boots the server, feeds a command script over stdin (server console), watches console.log
#   5. asserts the translated components really appear (via /data get ...), prints PASS/FAIL, exits 0/1
#
# No player is required: verification uses /item replace block + /data get block, exactly like the task asks.
#
# Log windows: every command is followed by "/say RNBT-SENT-<n>"; console input is handled in order on
# the server thread, so the sentinel lines delimit each command's output. The windows are sliced once,
# after the whole script has run, which removes all polling/timing races.
#
# The world is regenerated before each run (-KeepWorld to opt out) so repeated runs are deterministic:
# a leftover chest/redstone block would make /setblock report "Could not set the block" and the command
# block would never see a fresh redstone edge.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools\acceptance\run-acceptance.ps1
#   powershell -ExecutionPolicy Bypass -File tools\acceptance\run-acceptance.ps1 -SkipDownload
#   powershell -ExecutionPolicy Bypass -File tools\acceptance\run-acceptance.ps1 -KeepWorld -BootTimeoutSeconds 600
#
# Notes:
#   - first boot downloads the vanilla 26.3 server + loader libs (a few minutes, needs network)
#   - the script never runs Gradle; rebuild the jar first (Lead runs .\gradlew.bat build)
param(
    [string]$ServerDir = "",
    [int]$BootTimeoutSeconds = 600,
    [int]$CommandWaitMs = 1200,
    [switch]$SkipDownload,
    [switch]$KeepWorld,
    [switch]$KeepRun,
    # Killing leftover servers can destroy a teammate's run, so it is opt-in.
    [switch]$ForceCleanup,
    # Per-line function fixtures (T6 format: <case>.mcfunction + <case>.expected.txt).
    [string]$FixtureDir = "",
    # Own port/dir so several agents can run acceptance side by side without touching each other.
    [int]$ServerPort = 25565
)

$ErrorActionPreference = "Continue"
$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
if (-not $ServerDir) { $ServerDir = Join-Path $root "tools\acceptance\run-server" }
if (-not $FixtureDir) { $FixtureDir = Join-Path $root "tools\acceptance\fixtures\functions" }
$jdk = Join-Path $env:USERPROFILE ".gradle\jdks\eclipse_adoptium-25-amd64-windows.2"
$javaExe = Join-Path $jdk "bin\java.exe"
$log = Join-Path $ServerDir "console.log"
$launcher = Join-Path $ServerDir "fabric-server-launch.jar"
$script:checks = New-Object System.Collections.ArrayList

# cmd.exe keeps console.log open for writing; plain Get-Content/ReadAllText hits a sharing
# violation, so every read goes through a FileStream with FileShare.ReadWrite.
function Read-Log([string]$path) {
    if (-not (Test-Path $path)) { return "" }
    for ($i = 0; $i -lt 3; $i++) {
        try {
            $fs = New-Object IO.FileStream($path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
            $sr = New-Object IO.StreamReader($fs)
            $text = $sr.ReadToEnd()
            $sr.Close(); $fs.Close()
            return $text
        } catch {
            Start-Sleep -Milliseconds 200
        }
    }
    return ""
}

function Get-LogLines([string]$path) {
    return @((Read-Log $path) -split "\r?\n")
}

function Add-Check([string]$name, [bool]$ok, [string]$detail) {
    [void]$script:checks.Add([pscustomobject]@{ Name = $name; Ok = $ok; Detail = $detail })
    $tag = if ($ok) { "PASS" } else { "FAIL" }
    Write-Output ("  [" + $tag + "] " + $name)
    if (-not $ok) {
        $snippet = ($detail -replace "\s+", " ")
        if ($snippet.Length -gt 500) { $snippet = $snippet.Substring(0, 500) + " ..." }
        Write-Output ("         evidence: " + $snippet)
    }
}

if (-not (Test-Path $javaExe)) { Write-Output "JDK 25 not found: $javaExe"; exit 2 }

# Process.Kill() only kills cmd.exe; the java server it spawned would survive and keep port 25565 bound,
# so the whole tree has to be killed through taskkill /T.
function Stop-ServerProcess($proc) {
    if ($null -eq $proc) { return }
    if (-not $proc.HasExited) {
        try { $proc.StandardInput.WriteLine("stop") } catch {}
        Start-Sleep -Seconds 8
    }
    if (-not $proc.HasExited) {
        & taskkill.exe /PID $proc.Id /T /F 2>&1 | Out-Null
        Start-Sleep -Seconds 2
    }
}

# If MY port is already taken (a leftover run, or a teammate's server on the same port), never kill it
# silently: refuse by default, -ForceCleanup to take over. A server on another port is none of my business.
$portBusy = $null -ne (Get-NetTCPConnection -LocalPort $ServerPort -ErrorAction SilentlyContinue)
$leftovers = @()
if ($portBusy) {
    $leftovers = @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like "*fabric-server-launch.jar*" })
}
if ($portBusy) {
    if ($ForceCleanup) {
        foreach ($leftover in $leftovers) {
            Write-Output ("=== killing leftover server pid " + $leftover.ProcessId)
            Stop-Process -Id $leftover.ProcessId -Force -ErrorAction SilentlyContinue
        }
        Start-Sleep -Seconds 3
    } else {
        foreach ($leftover in $leftovers) {
            Write-Output ("a fabric server is already running: pid " + $leftover.ProcessId)
        }
        Write-Output ("refusing to start (port " + $ServerPort + " is in use); rerun with -ForceCleanup to take over, or pass -ServerPort")
        exit 2
    }
}

# ---------------------------------------------------------------------------
# 1. run-server/ layout
# ---------------------------------------------------------------------------
New-Item -ItemType Directory -Force -Path $ServerDir | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $ServerDir "mods") | Out-Null
Write-Output ("=== server dir: " + $ServerDir)

# ---------------------------------------------------------------------------
# 2. launcher download
# ---------------------------------------------------------------------------
if (-not (Test-Path $launcher)) {
    if ($SkipDownload) { Write-Output "missing launcher and -SkipDownload given: $launcher"; exit 2 }
    $installer = "1.1.2"
    try {
        $meta = Invoke-RestMethod -Uri "https://meta.fabricmc.net/v2/versions/installer" -TimeoutSec 30
        if ($meta -and $meta.Count -gt 0) { $installer = $meta[0].version }
    } catch { Write-Output ("  (installer meta lookup failed, using " + $installer + ": " + $_.Exception.Message + ")") }
    $url = "https://meta.fabricmc.net/v2/versions/loader/26.3/0.19.5/" + $installer + "/server/jar"
    Write-Output ("=== download launcher: " + $url)
    try {
        Invoke-WebRequest -Uri $url -OutFile $launcher -TimeoutSec 300 -UseBasicParsing
    } catch {
        Write-Output ("download failed: " + $_.Exception.Message)
        exit 2
    }
} else {
    Write-Output "=== launcher already present (reusing)"
}

# ---------------------------------------------------------------------------
# 3. eula.txt + server.properties
# ---------------------------------------------------------------------------
Set-Content -Path (Join-Path $ServerDir "eula.txt") -Value "eula=true" -Encoding ASCII
# NOTE: build this as its own statement. Inside an array literal PowerShell does not parse
# "server-port=" + $ServerPort as one element, which silently produced an invalid properties line
# ("25579=") plus a stray server-port entry (found by the independent verifier).
$portLine = "server-port=" + $ServerPort
$props = @(
    "online-mode=false",
    "enable-rcon=false",
    "level-type=minecraft\:flat",
    "spawn-protection=0",
    "max-tick-time=-1",
    "enable-command-block=true",
    "gamemode=creative",
    # normal, not peaceful: /summon minecraft:zombie is refused in Peaceful ("Monsters cannot be summoned")
    "difficulty=normal",
    "spawn-monsters=false",
    "view-distance=4",
    "simulation-distance=4",
    "sync-chunk-writes=false",
    "motd=RegainNBT acceptance",
    $portLine,
    "level-name=world"
)
Set-Content -Path (Join-Path $ServerDir "server.properties") -Value $props -Encoding ASCII
Write-Output "=== eula.txt + server.properties written"

if (-not $KeepWorld) {
    $worldDir = Join-Path $ServerDir "world"
    if (Test-Path $worldDir) { Remove-Item $worldDir -Recurse -Force -ErrorAction SilentlyContinue }
    Write-Output "=== world reset (deterministic reruns)"
}

# ---------------------------------------------------------------------------
# 3b. datapack fixture: one function line with legacy NBT, translated at load time (T6 path).
#     It must exist before the world is generated; the folder survives world creation.
#     pack_format 121 = 26.3 data pack version (SharedConstants#packVersion(SERVER_DATA), Probe5).
#     Both "function/" (1.21+) and "functions/" (older) are written so the fixture works on either.
# ---------------------------------------------------------------------------
$packRoot = Join-Path $ServerDir "world\datapacks\rnbt_acceptance"
New-Item -ItemType Directory -Force -Path $packRoot | Out-Null
Set-Content -Path (Join-Path $packRoot "pack.mcmeta") -Encoding ASCII -Value '{ "pack": { "pack_format": 121, "description": "RegainNBT acceptance" } }'
$functionLine = 'setblock 6 -60 0 minecraft:chest{Items:[{Slot:0b,id:"minecraft:diamond",Count:3b}]}'
foreach ($dirName in @("function", "functions")) {
    $fnDir = Join-Path $packRoot ("data\rnbt_acceptance\" + $dirName)
    New-Item -ItemType Directory -Force -Path $fnDir | Out-Null
    Set-Content -Path (Join-Path $fnDir "legacy.mcfunction") -Encoding ASCII -Value $functionLine
}
Write-Output "=== datapack fixture written (rnbt_acceptance:legacy)"

# Per-line fixtures (T6 format). Files are copied byte-exact: splice_continuation.mcfunction relies on a
# trailing backslash at the end of a physical line.
$fixtureCases = @()
if (Test-Path $FixtureDir) {
    $fixtureCases = @(Get-ChildItem $FixtureDir -Filter "*.expected.txt" | Sort-Object Name)
    foreach ($case in $fixtureCases) {
        $fixtureName = $case.Name -replace ".expected.txt$", ""
        $fixtureFile = Join-Path $FixtureDir ($fixtureName + ".mcfunction")
        if (-not (Test-Path $fixtureFile)) { continue }
        foreach ($dirName in @("function", "functions")) {
            $fnDir = Join-Path $packRoot ("data\rnbt_acceptance\" + $dirName)
            New-Item -ItemType Directory -Force -Path $fnDir | Out-Null
            Copy-Item $fixtureFile -Destination (Join-Path $fnDir ($fixtureName + ".mcfunction")) -Force
        }
    }
    Write-Output ("=== function fixtures copied: " + $fixtureCases.Count + " case(s) from " + $FixtureDir)
} else {
    Write-Output ("=== no function fixtures at " + $FixtureDir + " (skipping that phase)")
}

# ---------------------------------------------------------------------------
# 4. mods: our jar + fabric-api (must be the 26.3 build!)
# ---------------------------------------------------------------------------
$modsDir = Join-Path $ServerDir "mods"
Get-ChildItem $modsDir -Filter "*.jar" -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue

$modJar = Get-ChildItem (Join-Path $root "build\libs") -Filter "regainnbt-*.jar" -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch "sources|dev|javadoc" } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $modJar) { Write-Output "no build/libs/regainnbt-*.jar -- build first (gradlew build)"; exit 2 }
Copy-Item $modJar.FullName -Destination $modsDir -Force
Write-Output ("=== mod jar: " + $modJar.Name + "  (" + $modJar.LastWriteTime + ")")

$cpFile = Join-Path $root "build\devcp\main.txt"
$apiJar = $null
if (Test-Path $cpFile) {
    $cp = (Get-Content $cpFile -Raw).Trim()
    $apiJar = ($cp -split ";") | Where-Object { $_ -match "fabric-api" -and $_ -match "26\.3" } | Select-Object -First 1
}
if (-not $apiJar) {
    # fallback: exact file name, never a wildcard across historical versions
    $apiJar = (Get-ChildItem "J:\mc\mods\.gradle-home\caches\modules-2\files-2.1\net.fabricmc.fabric-api" -Recurse -Filter "fabric-api-0.161.0+26.3.jar" -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName)
}
if (-not $apiJar -or -not (Test-Path $apiJar)) {
    Write-Output "fabric-api 26.3 jar not found (needed: HARD_DEP_NO_CANDIDATE without it)"; exit 2
}
Copy-Item $apiJar -Destination $modsDir -Force
Write-Output ("=== fabric-api: " + (Split-Path $apiJar -Leaf))

# ---------------------------------------------------------------------------
# 5. boot the server (cmd.exe redirect, stdin kept open for console commands)
# ---------------------------------------------------------------------------
Remove-Item $log -Force -ErrorAction SilentlyContinue
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = "cmd.exe"
$psi.Arguments = '/c ""' + $javaExe + '" -Xmx2G -jar fabric-server-launch.jar nogui > console.log 2>&1"'
$psi.WorkingDirectory = $ServerDir
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$psi.CreateNoWindow = $true
$proc = [System.Diagnostics.Process]::Start($psi)
Write-Output ("=== server pid " + $proc.Id + ", waiting for boot (timeout " + $BootTimeoutSeconds + "s)")

$booted = $false
$deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 5
    $text = Read-Log $log
    if ($text.Contains("Done (") -or $text.Contains('For help, type "help"')) { $booted = $true; break }
    if ($text.Contains("Failed to start the minecraft server") -or $text.Contains("A fatal error")) { break }
    if ($proc.HasExited) { break }
}
if (-not $booted) {
    Write-Output "=== SERVER DID NOT BOOT; tail of console.log ==="
    Get-LogLines $log | Select-Object -Last 60
    Stop-ServerProcess $proc
    exit 2
}
Write-Output "=== server booted"
$bootLog = Read-Log $log

# ---------------------------------------------------------------------------
# 6. send the command script (each command followed by a sentinel)
# ---------------------------------------------------------------------------
$q = [string][char]34
$legacyItem = '/item replace block 0 -60 0 container.0 with diamond_sword{Enchantments:[{id:' + $q + 'minecraft:sharpness' + $q + ',lvl:5}],display:{Name:''{' + $q + 'text' + $q + ':' + $q + 'Excalibur' + $q + '}''}}'
$cmdBlock = '/setblock 2 -60 0 minecraft:command_block{Command:' + $q + 'give Steve diamond_sword{Enchantments:[{id:\' + $q + 'minecraft:sharpness\' + $q + ',lvl:5}]}' + $q + ',auto:1b}'
$summon = '/summon minecraft:zombie 4 -60 0 {HandItems:[{id:' + $q + 'minecraft:diamond_sword' + $q + ',Count:1b,tag:{Enchantments:[{id:' + $q + 'minecraft:sharpness' + $q + ',lvl:2}]}},{}]}'
$control = '/give Steve diamond_sword[custom_name=' + $q + 'Modern' + $q + ']'

$script:cmds = @(
    "/forceload add -16 -16 16 16",
    "/setblock 0 -60 0 minecraft:chest",
    $legacyItem,
    "/data get block 0 -60 0 Items[0]",
    $cmdBlock,
    "/setblock 2 -59 0 minecraft:redstone_block",
    "/data get block 2 -60 0 Command",
    $summon,
    "/data get entity @e[type=minecraft:zombie,limit=1]",
    $control,
    "/function rnbt_acceptance:legacy",
    "/data get block 6 -60 0 Items[0]"
)
$script:preCmds = @("/regainnbt status")

Write-Output "=== running command script"
$seq = 0
foreach ($cmd in ($script:preCmds + $script:cmds)) {
    $seq++
    try { $proc.StandardInput.WriteLine($cmd) } catch {}
    Start-Sleep -Milliseconds $CommandWaitMs
    try { $proc.StandardInput.WriteLine("/say RNBT-SENT-" + $seq) } catch {}
    Start-Sleep -Milliseconds 300
}
Start-Sleep -Seconds 3

# ---------------------------------------------------------------------------
# 6b. per-line function fixtures: setup / run / probe, each delimited by its own marker
# ---------------------------------------------------------------------------
$script:fixtureMarkers = New-Object System.Collections.ArrayList
$script:fixturePlans = New-Object System.Collections.ArrayList
foreach ($case in $fixtureCases) {
    $fixtureName = $case.Name -replace ".expected.txt$", ""
    $spec = @{ name = $fixtureName; setup = @(); run = @(); probe = @(); contains = @(); notContains = @() }
    foreach ($raw in (Get-Content $case.FullName -Encoding UTF8)) {
        $line = $raw.Trim()
        if ($line.Length -eq 0 -or $line.StartsWith("#")) { continue }
        $sep = $line.IndexOf(":")
        if ($sep -lt 0) { continue }
        $key = $line.Substring(0, $sep).Trim()
        $value = $line.Substring($sep + 1).Trim()
        switch ($key) {
            "setup" { $spec.setup += $value }
            "run" { $spec.run += $value }
            "probe" { $spec.probe += $value }
            "contains" { $spec.contains += $value }
            "not_contains" { $spec.notContains += $value }
        }
    }
    $steps = New-Object System.Collections.ArrayList
    foreach ($step in @("setup", "run", "probe")) {
        $commands = $spec[$step]
        foreach ($command in $commands) {
            if (-not $command) { continue }
            $marker = "RNBT-FIX-" + $fixtureName + "-" + $step
            [void]$script:fixtureMarkers.Add($marker)
            [void]$steps.Add($marker)
            try { $proc.StandardInput.WriteLine("/" + $command) } catch {}
            Start-Sleep -Milliseconds $CommandWaitMs
            try { $proc.StandardInput.WriteLine("/say " + $marker) } catch {}
            Start-Sleep -Milliseconds 300
        }
    }
    $spec.markers = $steps
    [void]$script:fixturePlans.Add($spec)
}
# /stop last: it has no marker because the server is already gone when the sentinel would run
try { $proc.StandardInput.WriteLine("/stop") } catch {}
Start-Sleep -Seconds 4

# ---------------------------------------------------------------------------
# 7. slice the log by sentinels (output of command N is between sentinel N-1 and N)
# ---------------------------------------------------------------------------
$lines = Get-LogLines $log
$sentinelIndex = @{}
for ($i = 0; $i -lt $lines.Count; $i++) {
    $m = [regex]::Match($lines[$i], "RNBT-SENT-(\d+)")
    if ($m.Success -and -not $sentinelIndex.ContainsKey([int]$m.Groups[1].Value)) {
        $sentinelIndex[[int]$m.Groups[1].Value] = $i
    }
}
function Window([int]$n) {
    $from = 0
    if ($sentinelIndex.ContainsKey($n - 1)) { $from = $sentinelIndex[$n - 1] + 1 }
    # The sentinel after "/stop" never arrives (the server is already shutting down), so a missing
    # sentinel means "everything from the previous sentinel to the end of the log".
    if (-not $sentinelIndex.ContainsKey($n)) {
        if ($from -ge $lines.Count) { return "" }
        return (($lines[$from..($lines.Count - 1)]) -join " | ")
    }
    $to = $sentinelIndex[$n]
    if ($to -lt $from) { return "" }
    return (($lines[$from..$to]) -join " | ")
}

$wStatus = Window 1
$wForceload = Window 2
$wChest = Window 3
$wItem = Window 4
$wDataItem = Window 5
$wCmdBlock = Window 6
$wRedstone = Window 7
$wDataCmd = Window 8
$wSummon = Window 9
$wDataEntity = Window 10
$wControl = Window 11
$wFunction = Window 12
$wDataFunction = Window 13

# fixture windows: walk the fixture markers in send order, each window ends at its own marker
$fixtureWindows = @{}
$cursor = 0
if ($sentinelIndex.ContainsKey(13)) { $cursor = $sentinelIndex[13] }
foreach ($marker in $script:fixtureMarkers) {
    for ($i = $cursor + 1; $i -lt $lines.Count; $i++) {
        if ($lines[$i].Contains($marker)) {
            if ($i -gt $cursor + 1) { $fixtureWindows[$marker] = (($lines[($cursor + 1)..($i - 1)]) -join " | ") } else { $fixtureWindows[$marker] = "" }
            $cursor = $i
            break
        }
    }
}
$wStop = ""
if ($cursor -lt $lines.Count - 1) { $wStop = (($lines[($cursor + 1)..($lines.Count - 1)]) -join " | ") }

# ---------------------------------------------------------------------------
# 8. assertions
# ---------------------------------------------------------------------------
Add-Check "server booted" ($bootLog.Contains("Done (")) "Done ( not found"
Add-Check "mod RegainNBT loaded" ($bootLog.Contains("[RegainNBT]")) "no [RegainNBT] line in boot log"
Add-Check "T6 /regainnbt status responds" (($wStatus.Contains("[RegainNBT]")) -or ($wStatus.Contains("enabled="))) $wStatus
Add-Check "origin chunks force-loaded (or already loaded)" (($wForceload.Contains("Marked")) -or ($wForceload.Contains("Already")) -or ($wForceload.Contains("Added")) -or ($wForceload.Contains("force load"))) $wForceload
Add-Check "chest placed at 0 -60 0" ($wChest.Contains("Changed the block at 0, -60, 0")) $wChest

$itemOk = ($wItem.Contains("Replaced")) -and (-not $wItem.Contains("Incorrect argument")) -and (-not $wItem.Contains("Unknown item")) -and (-not $wItem.Contains("trailing data"))
Add-Check "T1 legacy /item replace ... with is translated and accepted" $itemOk $wItem
Add-Check "item got minecraft:enchantments component" ($wDataItem.Contains("minecraft:enchantments")) $wDataItem
Add-Check "item got custom_name component (display.Name translated)" (($wDataItem.Contains("custom_name")) -or ($wDataItem.Contains("Excalibur"))) $wDataItem

Add-Check "command block placed with legacy command" ($wCmdBlock.Contains("Changed the block at 2, -60, 0")) $wCmdBlock
Add-Check "redstone trigger executed (command block ran)" (($wRedstone.Contains("Changed the block at 2, -59, 0")) -and (-not $wRedstone.Contains("Unknown command"))) $wRedstone
Add-Check "T5 command block rewritten to old.give (auto-mark)" ($wDataCmd.Contains("old.give")) $wDataCmd

$summonOk = ($wSummon.Contains("Summoned new")) -and (-not $wSummon.Contains("Incorrect argument"))
Add-Check "T1 legacy /summon accepted" $summonOk $wSummon
Add-Check "zombie entity got equipment (HandItems translated)" ($wDataEntity.Contains("equipment")) $wDataEntity
Add-Check "zombie mainhand holds the sword with enchantments" (($wDataEntity.Contains("diamond_sword")) -and ($wDataEntity.Contains("minecraft:enchantments"))) $wDataEntity

$modernOk = ($wControl.Contains("No player was found")) -and (-not $wControl.Contains("trailing data"))
Add-Check "control: modern syntax is NOT rewritten (only vanilla no-player error)" $modernOk $wControl
Add-Check "T6 datapack function loaded (rnbt_acceptance:legacy)" (($wFunction.Contains("Unknown function") -eq $false) -and ($wFunction.Contains("System chat"))) $wFunction
Add-Check "T6 function line translated at load time (Count:3b -> count:3)" (($wDataFunction.Contains("count: 3")) -and (-not $wDataFunction.Contains("Count: 3b"))) $wDataFunction
foreach ($spec in $script:fixturePlans) {
    $runMarker = "RNBT-FIX-" + $spec.name + "-run"
    $probeMarker = "RNBT-FIX-" + $spec.name + "-probe"
    $runWindow = if ($fixtureWindows.ContainsKey($runMarker)) { $fixtureWindows[$runMarker] } else { "" }
    $probeWindow = if ($fixtureWindows.ContainsKey($probeMarker)) { $fixtureWindows[$probeMarker] } else { "" }
    $ok = $true
    $why = ""
    if ($runWindow.Contains("Unknown function")) { $ok = $false; $why += "function not loaded; " }
    foreach ($needle in $spec.contains) {
        if (-not $probeWindow.Contains($needle)) { $ok = $false; $why += ("missing [" + $needle + "]; ") }
    }
    foreach ($needle in $spec.notContains) {
        if ($probeWindow.Contains($needle)) { $ok = $false; $why += ("unexpected [" + $needle + "]; ") }
    }
    Add-Check ("T6 fixture: " + $spec.name) $ok ($why + "probe=" + $probeWindow)
}
Add-Check "/stop accepted" (($wStop.Contains("Stopping the server")) -or ($wStop.Contains("Saving"))) $wStop

# ---------------------------------------------------------------------------
# 9. summary
# ---------------------------------------------------------------------------
$passed = @($script:checks | Where-Object { $_.Ok }).Count
$failed = @($script:checks | Where-Object { -not $_.Ok }).Count
Write-Output ""
Write-Output "=== ACCEPTANCE SUMMARY ==="
foreach ($c in $script:checks) {
    $tag = if ($c.Ok) { "PASS" } else { "FAIL" }
    Write-Output ("  [" + $tag + "] " + $c.Name)
}
Write-Output ("=== passed=" + $passed + " failed=" + $failed + " ===")

if (-not $KeepRun) { Stop-ServerProcess $proc }
Write-Output ("=== console log: " + $log)
if ($failed -gt 0) { Write-Output "=== last 40 log lines ==="; Get-LogLines $log | Select-Object -Last 40 }
if ($failed -gt 0) { exit 1 } else { exit 0 }