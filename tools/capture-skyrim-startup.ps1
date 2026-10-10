#requires -Version 5.1
[CmdletBinding()]
param(
    [ValidateRange(1, 600)][int]$WaitSeconds = 180,
    [ValidateRange(1, 600)][int]$ObserveSeconds = 120,
    [int]$ProcessId = 0,
    [switch]$WaitForNewProcess,
    [switch]$LoadSaveTest,
    [string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
if (-not $OutputDirectory) { $OutputDirectory = Join-Path (Split-Path -Parent $PSScriptRoot) 'reports' }
$reportPath = Join-Path $OutputDirectory ('skyrim-startup-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 6))
New-Item -ItemType Directory -Path $reportPath -Force | Out-Null
$begin = [DateTime]::UtcNow
$ignoredIds = @()
if ($WaitForNewProcess) { $ignoredIds = @(Get-Process -Name SkyrimSE -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id) }
$report = [ordered]@{StartedUtc=$begin.ToString('o'); Status='waiting'; ProcessId=$null; IgnoredProcessIds=$ignoredIds; ObservedSeconds=0; ExitCode=$null; ExitCodeHex=$null; Executable=$null; FileVersion=$null; Errors=@()}
if ($LoadSaveTest) {
    Write-Host 'Ожидание Skyrim. Запустите SKSE через MO2, загрузите отдельное тестовое сохранение и проверьте HUD и движение. Не сохраняйтесь поверх основного сохранения.'
} else {
    Write-Host 'Ожидание Skyrim. Запустите проверяемый вариант игры. Достаточно главного меню; сохранение пока не загружайте.'
}
Write-Host 'Этот скрипт только наблюдает процесс и копирует диагностические логи. Игры и настройки он не меняет.'
$gameProcess = $null
try {
    $deadline = [DateTime]::UtcNow.AddSeconds($WaitSeconds)
    do {
        if ($ProcessId -gt 0) { $gameProcess = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue }
        else { $gameProcess = Get-Process -Name SkyrimSE -ErrorAction SilentlyContinue | Where-Object { $_.Id -notin $ignoredIds } | Select-Object -First 1 }
        if (-not $gameProcess) { Start-Sleep -Milliseconds 250 }
    } while (-not $gameProcess -and [DateTime]::UtcNow -lt $deadline)
    if (-not $gameProcess) { $report.Status = 'not_observed' }
    else {
        # Retain a process handle while it is alive so ExitCode remains available after termination.
        $gameProcess.Refresh()
        $null = $gameProcess.Handle
        $report.ProcessId = $gameProcess.Id
        try { $report.Executable = $gameProcess.MainModule.FileName; $report.FileVersion = $gameProcess.MainModule.FileVersionInfo.FileVersion } catch { $report.Errors += $_.Exception.GetType().Name }
        Write-Host "Найден процесс $($gameProcess.Id). Ожидание завершения, максимум $ObserveSeconds секунд."
        $watch = [Diagnostics.Stopwatch]::StartNew()
        if ($gameProcess.WaitForExit($ObserveSeconds * 1000)) {
            $report.Status = 'exited'
            $report.ExitCode = $gameProcess.ExitCode
            $unsigned = [BitConverter]::ToUInt32([BitConverter]::GetBytes([int]$gameProcess.ExitCode), 0)
            $report.ExitCodeHex = '0x{0:X8}' -f $unsigned
            Write-Host "Skyrim завершился: $($report.ExitCodeHex)."
        } else { $report.Status = 'still_running'; Write-Host 'Процесс остался запущенным; скрипт его не закрывает.' }
        $report.ObservedSeconds = [Math]::Round($watch.Elapsed.TotalSeconds, 2)
    }
} catch { $report.Status = 'observation_error'; $report.Errors += $_.Exception.GetType().Name }
finally { if ($gameProcess) { $gameProcess.Dispose() } }
$report.EndedUtc = [DateTime]::UtcNow.ToString('o')
$report | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $reportPath 'process.json') -Encoding UTF8
$skseDirectory = Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'My Games\Skyrim Special Edition\SKSE'
foreach ($name in @('SkyCraft.log','skse64.log','skse64_loader.log')) {
    $path = Join-Path $skseDirectory $name
    if (Test-Path -LiteralPath $path) { Copy-Item -LiteralPath $path -Destination $reportPath }
}
if ($LoadSaveTest) {
    $evidence = [ordered]@{Mode='save_load'; NativeLogFresh=$false; BuildLine=$null; WorldChanges=@(); PuppetOn=@(); CrashMarkers=@(); FreshDump=$false; Verdict='needs_user_confirmation'}
    $nativeLog = Join-Path $skseDirectory 'SkyCraft.log'
    if ((Test-Path -LiteralPath $nativeLog) -and (Get-Item -LiteralPath $nativeLog).LastWriteTimeUtc -ge $begin) {
        $evidence.NativeLogFresh = $true
        $lines = @(Get-Content -LiteralPath $nativeLog -Encoding UTF8)
        $evidence.BuildLine = @($lines | Where-Object { $_ -match 'SkyCraft .* loading \(runtime' } | Select-Object -First 1)
        $evidence.WorldChanges = @($lines | Where-Object { $_ -match 'world changed' })
        $evidence.PuppetOn = @($lines | Where-Object { $_ -match 'puppet on' })
        $evidence.CrashMarkers = @($lines | Where-Object { $_ -match 'CRASH exception' })
    }
    $dump = Join-Path $skseDirectory 'SkyCraft_crash.dmp'
    if ((Test-Path -LiteralPath $dump) -and (Get-Item -LiteralPath $dump).LastWriteTimeUtc -ge $begin) {
        Copy-Item -LiteralPath $dump -Destination $reportPath
        $evidence.FreshDump = $true
    }
    $evidence | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $reportPath 'save-load.json') -Encoding UTF8
}
try {
    $events = @(Get-WinEvent -FilterHashtable @{LogName='Application'; Id=1000,1001; StartTime=$begin.ToLocalTime().AddSeconds(-5)} -ErrorAction SilentlyContinue |
        Where-Object Message -Match 'SkyrimSE' | Select-Object TimeCreated, Id, ProviderName, Message)
    ConvertTo-Json -InputObject $events -Depth 4 | Set-Content -LiteralPath (Join-Path $reportPath 'windows-events.json') -Encoding UTF8
} catch { $_.Exception.GetType().Name | Set-Content -LiteralPath (Join-Path $reportPath 'event-read-error.txt') -Encoding UTF8 }
Write-Host "Отчёт: $reportPath"
Write-Host 'process.json содержит код завершения. Старые SKSE-логи приложены для сравнения: обычный запуск их не обновляет.'
