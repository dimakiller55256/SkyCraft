#requires -Version 5.1
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'skycraft-package-common.ps1')
Write-Host 'SkyCraft: 1 = отчёт запуска/HUD/движения; 2 = сетевой прогон с debug start/stop.'
$mode = Read-Host 'Режим [1]'
if ($mode -eq '2') { & (Join-Path $PSScriptRoot 'test-report-wizard.ps1'); return }
$suggested = Find-SkyCraftGameDirectory
$game = Read-Host "Каталог Minecraft с mods/config/logs [$suggested]"
if ([string]::IsNullOrWhiteSpace($game)) { $game = $suggested }
$notes = Read-Host 'Что произошло: меню, загрузка, HUD, движение или точное сообщение ошибки (без секретов)'
Write-Host 'По умолчанию включаются JSONL и сведения о Skyrim, без полных игровых логов.'
Write-Host 'Полные логи помогают разобрать запуск, но могут содержать чат, имена и параметры. Просмотрите ZIP перед передачей.'
$full = Read-Host 'Добавить latest.log, SkyCraft.log и логи SKSE? yes / no [no]'
& (Join-Path $PSScriptRoot 'collect-network-report.ps1') -GameDirectory $game.Trim('"') -RunId STARTUP -Role Client -Result partial -Notes $notes -IncludeLatestAutoTrace -IncludeGameLogs:($full -eq 'yes')
Write-Host 'Передайте создателю сборки полученный ZIP из reports. Ничего не отправлялось автоматически.'
