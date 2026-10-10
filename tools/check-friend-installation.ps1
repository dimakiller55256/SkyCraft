#requires -Version 5.1
[CmdletBinding()]
param([string]$GameDirectory, [string]$SkyrimDirectory, [string]$ModsDirectory, [string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'skycraft-package-common.ps1')
$manifest = Get-SkyCraftPackageManifest
if (-not $GameDirectory) {
    $suggested = Find-SkyCraftGameDirectory
    $GameDirectory = Read-Host "Каталог Minecraft с mods/config/logs [$suggested]"
    if ([string]::IsNullOrWhiteSpace($GameDirectory)) { $GameDirectory = $suggested }
}
if (-not $SkyrimDirectory) { $SkyrimDirectory = Read-Host 'Каталог Skyrim с SkyrimSE.exe (не Data и не папка мода)' }
if (-not $OutputDirectory) { $OutputDirectory = Join-Path (Split-Path -Parent $PSScriptRoot) 'reports' }
$reportPath = Join-Path $OutputDirectory ('installation-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,6))
New-Item -ItemType Directory -Path $reportPath -Force | Out-Null
$checks = New-Object 'System.Collections.Generic.List[object]'
$details = [ordered]@{Schema=1; Utc=[DateTime]::UtcNow.ToString('o'); ExpectedVersion=$manifest.version; Checks=$null}
function Check([string]$id, [string]$status, [string]$message) { $checks.Add([pscustomobject]@{Id=$id; Status=$status; Message=$message}) }
try {
    $gamePath = (Resolve-Path -LiteralPath $GameDirectory.Trim('"')).Path
    $modsPath = Join-Path $gamePath 'mods'
    if (-not (Test-Path -LiteralPath $modsPath -PathType Container)) { throw 'Missing mods' }
    $details.MinecraftDirectory = $gamePath
    $mods = @()
    foreach ($file in Get-ChildItem -LiteralPath $modsPath -File -Filter '*.jar') {
        try { $meta = Read-FabricMetadata $file.FullName }
        catch { Check 'jar_read' 'WARN' "Не прочитан JAR $($file.Name): $($_.Exception.GetType().Name)"; continue }
        if ($meta.id -in @('skycraft','e4mc','fabric-api')) { $mods += [pscustomobject]@{File=$file.Name; Id=$meta.id; Version=$meta.version; Sha256=(Get-SkyCraftFileHash -LiteralPath $file.FullName).Hash} }
    }
    $details.Mods = $mods
    $skycraft = @($mods | Where-Object Id -eq 'skycraft')
    if ($skycraft.Count -ne 1) { Check 'skycraft_count' 'FAIL' "SkyCraft JAR должно быть ровно 1; найдено $($skycraft.Count). Запустите Установить обновление.cmd." }
    elseif ($skycraft[0].Sha256 -ne $manifest.fabricJar.sha256 -or $skycraft[0].Version -ne $manifest.version) { Check 'skycraft_version' 'FAIL' 'Установлена другая Java-сборка SkyCraft.' }
    else { Check 'skycraft_version' 'PASS' "SkyCraft $($manifest.version), SHA256 совпал." }
    if (@($mods | Where-Object Id -eq 'e4mc').Count) { Check 'e4mc' 'FAIL' 'Остался активный e4mc JAR; установщик обновления перенесёт его в резервную копию.' } else { Check 'e4mc' 'PASS' 'Активного e4mc JAR нет.' }
    $api = @($mods | Where-Object Id -eq 'fabric-api')
    if ($api.Count -eq 1 -and $api[0].Version -eq $manifest.requirements.fabricApi) { Check 'fabric_api' 'PASS' "Fabric API $($api[0].Version)" } else { Check 'fabric_api' 'FAIL' "Нужен один Fabric API $($manifest.requirements.fabricApi). Используйте чистый экземпляр из комплекта или проверьте Prism → Моды." }
    $packFile = Join-Path (Split-Path -Parent $gamePath) 'mmc-pack.json'
    if (Test-Path -LiteralPath $packFile) {
        $components = (Get-Content -LiteralPath $packFile -Raw -Encoding UTF8 | ConvertFrom-Json).components
        $details.PrismComponents = @($components | Where-Object uid -in @('net.minecraft','net.fabricmc.fabric-loader') | Select-Object uid,version)
        $mc = @($components | Where-Object uid -eq 'net.minecraft')
        $loader = @($components | Where-Object uid -eq 'net.fabricmc.fabric-loader')
        if ($mc.Count -eq 1 -and $mc[0].version -eq $manifest.requirements.minecraft) { Check 'minecraft' 'PASS' "Minecraft $($mc[0].version)" } else { Check 'minecraft' 'FAIL' "В Prism → Версии выберите Minecraft $($manifest.requirements.minecraft)." }
        if ($loader.Count -eq 1 -and $loader[0].version -eq $manifest.requirements.fabricLoader) { Check 'fabric_loader' 'PASS' "Fabric Loader $($loader[0].version)" } else { Check 'fabric_loader' 'WARN' "Проверенная версия Loader: $($manifest.requirements.fabricLoader); сравните её с Prism → Версии." }
    } else { Check 'prism_versions' 'WARN' 'mmc-pack.json не найден; версии Minecraft/Loader проверьте в лаунчере вручную.' }
    $instanceFile = Join-Path (Split-Path -Parent $gamePath) 'instance.cfg'
    if (Test-Path -LiteralPath $instanceFile) {
        $instanceText = [IO.File]::ReadAllText($instanceFile)
        $details.InvalidApiOverridePresent = [bool]($instanceText -match 'minecraft\.api\.[^=\s]+\.host=https://nope\.invalid')
        if ($details.InvalidApiOverridePresent) { Check 'api_override' 'WARN' 'Есть переопределение адресов Minecraft API на nope.invalid. Оно может нарушать сетевую авторизацию; проверьте JVM-параметры экземпляра.' }
    }
} catch { Check 'minecraft_directory' 'FAIL' "Не удалось проверить каталог Minecraft: $($_.Exception.GetType().Name). Укажите папку с mods/config/logs." }
try {
    $skyrimPath = (Resolve-Path -LiteralPath $SkyrimDirectory.Trim('"')).Path
    $exe = Get-Item -LiteralPath (Join-Path $skyrimPath 'SkyrimSE.exe')
    $version = $exe.VersionInfo.FileVersion
    $details.Skyrim = [ordered]@{Directory=$skyrimPath; Version=$version}
    if ($version -eq $manifest.requirements.skyrimRuntime) { Check 'skyrim_runtime' 'PASS' "Skyrim runtime $version" } else { Check 'skyrim_runtime' 'WARN' "Runtime $version отличается от проверенного $($manifest.requirements.skyrimRuntime); не считать совместимость подтверждённой." }
    if ((Test-Path -LiteralPath (Join-Path $skyrimPath 'skse64_loader.exe')) -and (Test-Path -LiteralPath (Join-Path $skyrimPath $manifest.requirements.skseRuntimeDll))) { Check 'skse_files' 'PASS' 'Есть SKSE loader и DLL для проверенного runtime. Запускайте SKSE через MO2.' } else { Check 'skse_files' 'FAIL' "Не найдены skse64_loader.exe / $($manifest.requirements.skseRuntimeDll) в каталоге игры." }
    if (-not $ModsDirectory) {
        $suggestedMods = ''
        if (Test-Path -LiteralPath (Join-Path $skyrimPath 'Mods')) { $suggestedMods = Join-Path $skyrimPath 'Mods' }
        Write-Host 'Путь модов: MO2 -> Настройки -> Пути -> Моды (Mods). Пусто без подсказки = пропустить поиск в MO2.'
        $ModsDirectory = Read-Host "Каталог модов MO2 [$suggestedMods]"
        if ([string]::IsNullOrWhiteSpace($ModsDirectory)) { $ModsDirectory = $suggestedMods }
        else { $ModsDirectory = $ModsDirectory.Trim('"') }
    }
    $pluginCandidates = @()
    $libraryCandidates = @()
    $roots = @((Join-Path $skyrimPath 'Data'))
    if ($ModsDirectory -and (Test-Path -LiteralPath $ModsDirectory)) { $roots += @(Get-ChildItem -LiteralPath $ModsDirectory -Directory | Where-Object { -not ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) } | Select-Object -ExpandProperty FullName) }
    foreach ($root in $roots) {
        $plugin = Join-Path $root 'SKSE\Plugins\SkyCraft.dll'
        $library = Join-Path $root ('SKSE\Plugins\' + $manifest.requirements.addressLibrary)
        if (Test-Path -LiteralPath $plugin) { $pluginCandidates += [pscustomobject]@{Path=$plugin; Sha256=(Get-SkyCraftFileHash -LiteralPath $plugin).Hash} }
        if (Test-Path -LiteralPath $library) { $libraryCandidates += $library }
    }
    $details.NativePluginCandidates = $pluginCandidates; $details.AddressLibraryCandidates = $libraryCandidates
    if (@($pluginCandidates | Where-Object Sha256 -eq $manifest.nativeDll.sha256).Count) { Check 'native_plugin' 'PASS' "Найдена SkyCraft.dll для комплекта $($manifest.version). Убедитесь, что именно её мод включён в MO2." } else { Check 'native_plugin' 'WARN' 'DLL из этого комплекта не найдена. Для исправления дверей/ввода/падения обновите также Skyrim-часть; укажите каталог mods MO2.' }
    if ($libraryCandidates.Count) { Check 'address_library' 'PASS' 'Файл Address Library для runtime найден. Его мод должен быть включён в MO2.' } else { Check 'address_library' 'WARN' 'Файл Address Library не найден; проверьте mods MO2 и библиотеку для runtime. Физические папки не показывают активность мода.' }
    if ($ModsDirectory -and [IO.Path]::GetFullPath($ModsDirectory).StartsWith($skyrimPath + '\', [StringComparison]::OrdinalIgnoreCase)) {
        $longPaths = New-Object 'System.Collections.Generic.List[string]'
        $pending = New-Object 'System.Collections.Generic.Stack[string]'; $pending.Push($ModsDirectory)
        $timer = [Diagnostics.Stopwatch]::StartNew(); $visited = 0; $complete = $true
        while ($pending.Count -gt 0) {
            if ($timer.Elapsed.TotalSeconds -gt 20 -or $visited -gt 100000) { $complete = $false; break }
            $folder = $pending.Pop()
            foreach ($entry in Get-ChildItem -LiteralPath $folder -Force) {
                $visited++
                if ($timer.Elapsed.TotalSeconds -gt 20 -or $visited -gt 100000) { $complete = $false; break }
                if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) { continue }
                $relative = $entry.FullName.Substring($skyrimPath.Length + 1).Replace('\','/')
                # The captured failure used ASCII. UTF-8 byte count is a conservative warning for other paths.
                if ([Text.Encoding]::UTF8.GetByteCount($relative) -ge 260 -and $longPaths.Count -lt 20) { $longPaths.Add($relative) }
                if ($entry.PSIsContainer) { $pending.Push($entry.FullName) }
            }
            if (-not $complete) { break }
        }
        $details.LongPathCheck = [ordered]@{Completed=$complete; Visited=$visited; Examples=@($longPaths.ToArray())}
        if ($longPaths.Count) { Check 'game_paths' 'FAIL' 'Внутри Skyrim/Mods найдены пути от 260 байт. Перенесите исходники/кэши наружу; не трогайте игровые сохранения.' }
        elseif (-not $complete) { Check 'game_paths' 'WARN' 'Ограничение времени/числа файлов; проверена только часть дерева Mods.' }
        else { Check 'game_paths' 'PASS' 'В проверенном Mods нет путей от 260 байт; ссылки не обходились.' }
    }
} catch { Check 'skyrim_directory' 'WARN' "Проверка Skyrim неполная: $($_.Exception.GetType().Name). Укажите каталог игры и каталог mods MO2." }
$details.Checks = @($checks.ToArray())
$details.Interpretation = 'PASS checks installation facts only. The menu, HUD, movement and real multiplayer must still be tested by the player.'
$details | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $reportPath 'installation.json') -Encoding UTF8
$lines = @('SkyCraft — проверка установки', 'PASS означает совпадение проверенного факта, не гарантию игры.', '')
$lines += @($checks | ForEach-Object { "[$($_.Status)] $($_.Id): $($_.Message)" })
$lines += @('', 'Проверьте Java 25 в Prism → настройки экземпляра → Java. После установки впервые могут потребоваться загрузки.', 'Ничего не изменялось в играх или настройках Windows. Аккаунты и полные конфиги не копировались.')
$lines | Set-Content -LiteralPath (Join-Path $reportPath 'ПРОВЕРКА.txt') -Encoding UTF8
$archive = $reportPath + '.zip'; [IO.Compression.ZipFile]::CreateFromDirectory($reportPath,$archive)
$checks | Format-Table Status,Id,Message -Wrap
Write-Host "Отчёт проверки: $archive"
