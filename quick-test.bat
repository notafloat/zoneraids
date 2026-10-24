<# : batch part, PowerShell sees this block as a comment
@echo off
rem Zone Raids quick test on this PC: downloads the latest green Modpack build from GitHub Actions,
rem updates a local server and a Prism Launcher instance, starts the server and joins it.
rem Usage: double-click, or  quick-test.bat [raids^|survival] [branch]
setlocal
title Zone Raids quick test
set "QT_SELF=%~f0"
set "QT_ARGS=%*"
powershell -NoProfile -ExecutionPolicy Bypass -Command "iex ([IO.File]::ReadAllText($env:QT_SELF))"
echo.
pause
exit /b
#>

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Add-Type -AssemblyName System.IO.Compression.FileSystem

$Repo = 'notafloat/zoneraids'
$Root = Join-Path $env:USERPROFILE 'ZoneRaids-test'
$Port = 25565

function Say($text, $color = 'Gray') { Write-Host $text -ForegroundColor $color }
function Fail($text) { throw $text }

function Download($url, $dest) {
    # Mod file names can contain [ ], which PowerShell cmdlets read as wildcards: download to a plain
    # temp name and move with .NET.
    $tmp = Join-Path $Root ("download-" + [guid]::NewGuid().ToString('N') + ".part")
    for ($i = 1; $i -le 4; $i++) {
        try {
            Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $tmp
            if ([IO.File]::Exists($dest)) { [IO.File]::Delete($dest) }
            [IO.File]::Move($tmp, $dest)
            return
        } catch {
            if ($i -eq 4) { throw "Не удалось скачать $url : $($_.Exception.Message)" }
            Start-Sleep -Seconds ([math]::Pow(2, $i))
        }
    }
}

# Extracts the zip into $dest; $strip removes a leading folder from entry names, $only keeps entries under it.
function Expand-Zip($zip, $dest, $strip = '', $only = '') {
    $full = [IO.Path]::GetFullPath($dest)
    $z = [IO.Compression.ZipFile]::OpenRead($zip)
    try {
        foreach ($e in $z.Entries) {
            $name = $e.FullName
            if ($only -and -not $name.StartsWith($only)) { continue }
            if ($strip -and $name.StartsWith($strip)) { $name = $name.Substring($strip.Length) }
            if (-not $name -or $name.EndsWith('/')) { continue }
            $out = [IO.Path]::GetFullPath((Join-Path $full $name))
            if (-not $out.StartsWith($full)) { continue }
            [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($out)) | Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($e, $out, $true)
        }
    } finally { $z.Dispose() }
}

# Prism reads its files as UTF-8; Set-Content -Encoding UTF8 in Windows PowerShell adds a BOM.
function Write-Utf8($path) {
    [IO.File]::WriteAllText($path, (@($input) -join "`r`n") + "`r`n", (New-Object Text.UTF8Encoding($false)))
}

function Read-ZipText($zip, $entry) {
    $z = [IO.Compression.ZipFile]::OpenRead($zip)
    try {
        $r = New-Object IO.StreamReader($z.GetEntry($entry).Open())
        try { $r.ReadToEnd() } finally { $r.Dispose() }
    } finally { $z.Dispose() }
}

function Test-Port {
    $c = New-Object Net.Sockets.TcpClient
    try { $c.Connect('127.0.0.1', $Port); $true } catch { $false } finally { $c.Close() }
}

function Find-Prism {
    $saved = "$Root\prism-path.txt"
    $candidates = @(
        $env:PRISM,
        $(if (Test-Path $saved) { (Get-Content $saved -Raw).Trim() }),
        $(Get-Process prismlauncher -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty Path),
        "$env:LOCALAPPDATA\Programs\PrismLauncher\prismlauncher.exe",
        "$env:ProgramFiles\PrismLauncher\prismlauncher.exe",
        "$env:USERPROFILE\scoop\apps\prismlauncher\current\prismlauncher.exe"
    )
    foreach ($p in $candidates) { if ($p -and (Test-Path $p)) { return $p } }
    $cmd = Get-Command prismlauncher.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

function Test-Java17($exe) {
    if (-not $exe -or -not (Test-Path $exe)) { return $false }
    $v = cmd /c "`"$exe`" -version 2>&1"
    return [bool]($v | Select-String -Quiet 'version "17\.')
}

# Java 17 already on the PC (JAVA, JAVA_HOME, PATH, usual install folders, Java that Prism downloaded).
function Find-Java17($extraDirs) {
    $exes = @($env:JAVA, "$env:JAVA_HOME\bin\java.exe")
    $exes += @(Get-Command java.exe -All -ErrorAction SilentlyContinue | ForEach-Object Source)
    $dirs = @("$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Java", "$env:ProgramFiles\Microsoft",
        "$env:ProgramFiles\Zulu", "$env:ProgramFiles\BellSoft", "$env:LOCALAPPDATA\Programs\Eclipse Adoptium") + $extraDirs
    foreach ($d in $dirs) {
        if ($d -and (Test-Path $d)) { $exes += @(Get-ChildItem $d -Recurse -Depth 3 -Filter java.exe -ErrorAction SilentlyContinue | ForEach-Object FullName) }
    }
    foreach ($e in $exes) { if (Test-Java17 $e) { return $e } }
    return $null
}

try {
    # ---- What to test ------------------------------------------------------
    $argv = @("$env:QT_ARGS" -split '\s+' | Where-Object { $_ })
    $variant = if ($argv.Count -ge 1) { $argv[0] } else { '' }
    $branch = if ($argv.Count -ge 2) { $argv[1] } else { '' }
    if (-not $variant) {
        Say 'Что тестируем?' White
        Say '  1 - рейды (по умолчанию)'
        Say '  2 - выживание'
        $variant = if ((Read-Host 'Номер') -eq '2') { 'survival' } else { 'raids' }
    }
    if ($variant -notin @('raids', 'survival')) { Fail "Неизвестный вариант '$variant', нужен raids или survival." }
    if (-not $branch) {
        $branch = Read-Host 'Ветка (Enter = main)'
        if (-not $branch) { $branch = 'main' }
    }
    $prefix = if ($variant -eq 'survival') { 'ZoneRaids-Survival' } else { 'ZoneRaids' }
    $dir = Join-Path $Root $variant
    New-Item -ItemType Directory -Force $dir | Out-Null
    $serverUpdated = $false

    # ---- Latest green build ------------------------------------------------
    # main: the latest-build pre-release (CI refreshes it on every green run). Other branches: Actions
    # artifacts through nightly.link, which works once its GitHub app is installed for the repository.
    $dl = "$Root\downloads\$variant"
    New-Item -ItemType Directory -Force $dl | Out-Null
    $serverZip = "$dl\$prefix-server.zip"
    $mrpack = "$dl\$prefix.mrpack"
    Say "Ищу последнюю зелёную сборку ($branch)..." Cyan
    if ($branch -eq 'main') {
        $rel = "https://github.com/$Repo/releases/download/latest-build"
        # Release assets come as application/octet-stream, so read the file instead of .Content (bytes).
        try { Download "$rel/$prefix-build.txt" "$dir\latest-build.txt"; $info = @((Get-Content "$dir\latest-build.txt" -Raw).Trim() -split '\s+') }
        catch { Fail "Сборка latest-build пока не опубликована, дождитесь зелёного запуска Modpack в main. ($($_.Exception.Message))" }
        $buildId = $info[0]
        Say ("Сборка #{0}, коммит {1}" -f $info[1], $info[2].Substring(0, 7))
    } else {
        $api = "https://api.github.com/repos/$Repo/actions/workflows/modpack.yml/runs?branch=$([uri]::EscapeDataString($branch))&status=success&per_page=1"
        $run = (Invoke-RestMethod -UseBasicParsing $api).workflow_runs | Select-Object -First 1
        if (-not $run) { Fail "В ветке $branch нет успешных запусков Modpack. Проверьте вкладку Actions на GitHub." }
        $buildId = "$($run.id)"
        Say ("Сборка #{0}: {1} ({2})" -f $run.run_number, $run.display_title, $run.head_sha.Substring(0, 7))
    }

    $stamp = Join-Path $dir 'build-run.txt'
    $have = if (Test-Path $stamp) { (Get-Content $stamp -Raw).Trim() } else { '' }
    $serverDir = Join-Path $dir 'server'
    $needUpdate = $have -ne $buildId
    $dlStamp = "$dl\build.txt"
    $downloaded = (Test-Path $dlStamp) -and (Get-Content $dlStamp -Raw).Trim() -eq $buildId -and (Test-Path $serverZip) -and (Test-Path $mrpack)
    if (-not $downloaded) {
        Remove-Item -Force "$dl\*" -ErrorAction SilentlyContinue
        if ($branch -eq 'main') {
            Say "Скачиваю сервер..." Cyan
            Download "$rel/$prefix-server.zip" $serverZip
            Say "Скачиваю клиент..." Cyan
            Download "$rel/$prefix.mrpack" $mrpack
        } else {
            foreach ($a in @("$prefix-server", "$prefix-client-mrpack")) {
                Say "Скачиваю $a..." Cyan
                $art = "$dl\artifact.zip"
                try { Download "https://nightly.link/$Repo/actions/runs/$buildId/$a.zip" $art }
                catch {
                    Start-Process "https://github.com/apps/nightly-link"
                    Fail "Не удалось скачать сборку ветки через nightly.link. Установите на открывшейся странице приложение nightly.link для репозитория zoneraids и запустите скрипт снова. Сборки ветки main качаются и без него."
                }
                Expand-Zip $art $dl
                Remove-Item $art
            }
            Get-ChildItem $dl -Filter '*-server.zip' | Select-Object -First 1 | Move-Item -Destination $serverZip -Force
            Get-ChildItem $dl -Filter '*.mrpack' | Where-Object { $_.FullName -ne $mrpack } | Select-Object -First 1 | Move-Item -Destination $mrpack -Force
            if (-not ((Test-Path $serverZip) -and (Test-Path $mrpack))) { Fail 'В артефактах сборки нет сервера или .mrpack.' }
        }
        Set-Content $dlStamp $buildId
    } elseif (-not $needUpdate) {
        Say 'Эта сборка уже стоит, скачивать нечего.' Green
    }

    # ---- Server ------------------------------------------------------------
    $running = Test-Port
    if ($running) {
        Say "На порту $Port уже работает сервер. Если это старая версия, закройте его окно (stop) и запустите скрипт снова." Yellow
    }
    if ($needUpdate -and -not $running) {
        Say 'Обновляю сервер...' Cyan
        # Mods are replaced completely; world, eula.txt and player data stay.
        foreach ($d in @('mods', 'config', 'defaultconfigs')) {
            if (Test-Path "$serverDir\$d") { Remove-Item -Recurse -Force "$serverDir\$d" }
        }
        Expand-Zip $serverZip $serverDir "$prefix-server/"
        $serverUpdated = $true
    }
    if (-not $running) {
        if ((Test-Path "$serverDir\world") -and (Read-Host 'Начать с чистого мира? Старый мир удалится (y/N)') -eq 'y') {
            Remove-Item -Recurse -Force "$serverDir\world"
            Say 'Старый мир удалён, сервер построит хаб и карту заново.' Yellow
        }
        # online-mode=false lets offline (non-Microsoft) launcher accounts join. The last answer is the default.
        $modeFile = "$Root\online-mode.txt"
        $online = if (Test-Path $modeFile) { (Get-Content $modeFile -Raw).Trim() } else { 'false' }
        $hint = if ($online -eq 'true') { 'Y/n' } else { 'y/N' }
        $ans = Read-Host "Online mode: только лицензионные аккаунты Microsoft? Для пиратского (offline) аккаунта ответьте n ($hint)"
        if ($ans -eq 'y') { $online = 'true' } elseif ($ans -eq 'n') { $online = 'false' }
        Set-Content $modeFile $online
        Say "online-mode=$online"
        $props = "$serverDir\server.properties"
        if (Test-Path $props) { (Get-Content $props) -replace '^online-mode=.*', "online-mode=$online" | Set-Content -Encoding ASCII $props }
        $eula = "$serverDir\eula.txt"
        if (-not ((Test-Path $eula) -and (Select-String -Quiet -Path $eula -Pattern '^eula=true'))) {
            Say 'Нужно принять лицензию Minecraft: https://aka.ms/MinecraftEULA' White
            if ((Read-Host 'Принять? (y/N)') -ne 'y') { Fail 'Без принятия EULA сервер не запустится.' }
            Set-Content -Encoding ASCII $eula 'eula=true'
        }
    }

    # ---- Client: Prism Launcher instance ------------------------------------
    $prism = Find-Prism
    if (-not $prism) {
        Say 'Prism Launcher не найден там, где он обычно стоит.' Yellow
        $p = (Read-Host 'Перетащите сюда prismlauncher.exe (или любой exe вашего Prism) и нажмите Enter. Пусто = поставить Prism через winget').Trim('"', ' ')
        if ($p -and (Test-Path $p)) {
            $prism = $p
            Set-Content "$Root\prism-path.txt" $p
        }
    }
    if (-not $prism) {
        if (Get-Command winget -ErrorAction SilentlyContinue) {
            Say 'Ставлю через winget...' Cyan
            winget install --id PrismLauncher.PrismLauncher -e --accept-source-agreements --accept-package-agreements | Out-Host
            $prism = Find-Prism
        }
        if (-not $prism) {
            Start-Process 'https://prismlauncher.org/download/windows/'
            Fail 'Установите Prism Launcher с открывшейся страницы и запустите скрипт снова.'
        }
    }
    # Portable builds (and many repacks) keep instances next to the exe.
    $exeDir = Split-Path $prism
    $prismHome = if ((Test-Path "$exeDir\portable.txt") -or (Test-Path "$exeDir\instances")) { $exeDir } else { "$env:APPDATA\PrismLauncher" }
    Say "Prism: $prism (данные: $prismHome)"
    # ---- Java 17: the one already installed, otherwise an own copy -------------
    $javaExe = Find-Java17 @("$prismHome\java", "$Root\java")
    if (-not $javaExe) {
        Say 'Java 17 не найдена, скачиваю (один раз)...' Cyan
        $jz = "$Root\downloads\java17.zip"
        Download 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jre/hotspot/normal/eclipse?project=jdk' $jz
        Expand-Zip $jz "$Root\java"
        Remove-Item $jz
        $javaExe = (Get-ChildItem "$Root\java" -Recurse -Filter java.exe | Select-Object -First 1).FullName
    }
    Say "Java: $javaExe"
    $javaw = Join-Path (Split-Path $javaExe) 'javaw.exe'
    if (-not (Test-Path $javaw)) { $javaw = $javaExe }
    $instName = "$prefix-test"
    $inst = Join-Path $prismHome "instances\$instName"
    $mc = Join-Path $inst 'minecraft'
    $newInstance = -not (Test-Path "$inst\instance.cfg")

    if ($needUpdate -or $newInstance) {
        Say 'Обновляю клиент в Prism Launcher...' Cyan
        $index = Read-ZipText $mrpack 'modrinth.index.json' | ConvertFrom-Json
        New-Item -ItemType Directory -Force "$mc\mods" | Out-Null

        $ramGb = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB)
        $maxMem = if ($ramGb -ge 12) { 6144 } else { 4096 }
        @(
            'InstanceType=OneSix',
            "name=Zone Raids test ($variant)",
            'OverrideMemory=true', 'MinMemAlloc=4096', "MaxMemAlloc=$maxMem",
            'OverrideJava=true', 'OverrideJavaLocation=true', 'AutomaticJava=false',
            ('JavaPath=' + $javaw.Replace('\', '/'))
        ) | Write-Utf8 "$inst\instance.cfg"
        @{ formatVersion = 1; components = @(
            @{ uid = 'net.minecraft'; version = $index.dependencies.minecraft; important = $true },
            @{ uid = 'net.minecraftforge'; version = $index.dependencies.forge }
        ) } | ConvertTo-Json -Depth 4 | Write-Utf8 "$inst\mmc-pack.json"

        # Mods from Modrinth: download what is missing or changed.
        $keep = @{}
        $files = @($index.files | Where-Object { -not $_.env -or $_.env.client -ne 'unsupported' })
        $n = 0
        foreach ($f in $files) {
            $n++
            $dest = [IO.Path]::GetFullPath((Join-Path $mc $f.path))
            if (-not $dest.StartsWith([IO.Path]::GetFullPath($mc))) { continue }
            $keep[$dest.ToLower()] = $true
            if ((Test-Path -LiteralPath $dest) -and (Get-FileHash -Algorithm SHA1 -LiteralPath $dest).Hash -eq $f.hashes.sha1.ToUpper()) { continue }
            Say ("  [{0}/{1}] {2}" -f $n, $files.Count, (Split-Path -Leaf $f.path))
            [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($dest)) | Out-Null
            Download $f.downloads[0] $dest
        }
        # Our jars and configs come as overrides; mods that left the pack are removed.
        $z = [IO.Compression.ZipFile]::OpenRead($mrpack)
        try {
            foreach ($e in $z.Entries) {
                if ($e.FullName -match '^(client-)?overrides/mods/(.+)$') { $keep[(Join-Path "$mc\mods" $Matches[2]).ToLower()] = $true }
            }
        } finally { $z.Dispose() }
        Get-ChildItem "$mc\mods" -Filter *.jar | Where-Object { -not $keep[$_.FullName.ToLower()] } | ForEach-Object {
            Say "  убираю $($_.Name)"
            Remove-Item -Force -LiteralPath $_.FullName
        }
        Expand-Zip $mrpack $mc 'overrides/' 'overrides/'
        Expand-Zip $mrpack $mc 'client-overrides/' 'client-overrides/'
    }
    # Remember the build only once the server has it too (a running server is not touched).
    if (-not $needUpdate -or $serverUpdated) { Set-Content $stamp $buildId }

    # ---- Start ---------------------------------------------------------------
    if (-not $running) {
        Say 'Запускаю сервер в отдельном окне...' Cyan
        $env:JAVA = $javaExe
        Start-Process -FilePath cmd.exe -ArgumentList '/c', 'start-windows.bat' -WorkingDirectory $serverDir | Out-Null
        Say 'Жду, пока сервер поднимется (первый запуск с установкой Forge и постройкой карты дольше)...'
        $deadline = (Get-Date).AddMinutes(15)
        while (-not (Test-Port)) {
            if ((Get-Date) -gt $deadline) { Fail 'Сервер не поднялся за 15 минут. Посмотрите его окно и logs\latest.log.' }
            Start-Sleep -Seconds 3
        }
        Say 'Сервер готов.' Green
    }

    if ($newInstance -and (Get-Process prismlauncher -ErrorAction SilentlyContinue)) {
        Say 'Закройте Prism Launcher, чтобы он увидел новый экземпляр, и нажмите Enter.' Yellow
        Read-Host | Out-Null
    }
    Say "Запускаю клиент ($instName) и захожу на localhost..." Cyan
    Start-Process -FilePath $prism -ArgumentList '--launch', $instName, '--server', "127.0.0.1:$Port" | Out-Null
    Say ''
    Say 'Готово. Если Prism попросит аккаунт, добавьте его (Microsoft или offline) и запустите скрипт ещё раз.' Green
    Say "Права админа: в окне сервера введите  op <ваш ник>. Остановить сервер: stop. Файлы теста: $Root" Green
} catch {
    Say ''
    Say "[!] $($_.Exception.Message)" Red
    exit 1
}
