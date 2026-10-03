#requires -Version 5.1
$ErrorActionPreference = 'Stop'
function Ask([string]$label, [string]$default) {
    $answer = Read-Host "$label [$default]"
    if ([string]::IsNullOrWhiteSpace($answer)) { return $default }
    return $answer.Trim().Trim('"')
}
function Choice([string]$label, [string]$default, [string[]]$choices) {
    do { $answer = Ask ($label + ': ' + ($choices -join ' / ')) $default } while ($answer -notin $choices)
    return $answer
}
Write-Host 'SkyCraft: сбор локального сетевого отчёта. Настройки Windows не меняются.'
Write-Host 'В игре завершите прогон: /skycraft debug stop, затем подождите 2 секунды.'
$suggestedGame = ''
foreach ($candidate in @('D:\PrismLauncher\instances\SkyCraft\minecraft', (Join-Path $env:APPDATA 'PrismLauncher\instances\SkyCraft\minecraft'))) {
    if (Test-Path -LiteralPath (Join-Path $candidate 'mods')) { $suggestedGame = $candidate; break }
}
$gameDirectory = Ask 'Каталог Minecraft (с папкой mods)' $suggestedGame
$runId = Ask 'Тот же ID, что в debug start, например L01_01' 'L01_01'
$role = Choice 'Роль этого ПК' 'Host' @('Host', 'Client')
$network = Choice 'Где соединяются игроки' 'Unknown' @('Loopback', 'LAN', 'Internet', 'Unknown')
$targetName = Ask 'IP или DNS-имя хоста без порта; пусто = пропустить DNS/маршрут' ''
$port = Ask 'Порт игрового хоста' '25565'
$zapret = Choice 'zapret во время прогона' 'unknown' @('off', 'on', 'unknown')
$cloudflare = Choice 'Cloudflare: dns = только DNS, traffic_dns = трафик и DNS' 'unknown' @('off', 'dns', 'traffic_dns', 'unknown')
$vpn = Choice 'VPN: tunnel = туннель, system_proxy = системный прокси' 'unknown' @('off', 'tunnel', 'system_proxy', 'unknown')
$result = Choice 'Итог: partial = проверена только часть, blocked = нет условий для теста' 'partial' @('pass', 'fail', 'blocked', 'not_run', 'partial')
$notes = Ask 'Что произошло; без паролей и токенов' ''
& (Join-Path $PSScriptRoot 'collect-network-report.ps1') -GameDirectory $gameDirectory -RunId $runId -Role $role -NetworkLabel $network -TargetHost $targetName -TargetPort $port -ZapretState $zapret -CloudflareState $cloudflare -VpnState $vpn -Result $result -Notes $notes
