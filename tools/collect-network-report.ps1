#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$GameDirectory,
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9_-]{1,64}$')][string]$RunId,
    [Parameter(Mandatory = $true)][ValidateSet('Host', 'Client')][string]$Role,
    [string]$TargetHost,
    [ValidateRange(1, 65535)][int]$TargetPort = 25565,
    [ValidateRange(0, 3600)][int]$WatchSeconds = 0,
    [ValidateSet('off', 'on', 'unknown')][string]$ZapretState = 'unknown',
    [ValidateSet('off', 'dns', 'traffic_dns', 'unknown')][string]$CloudflareState = 'unknown',
    [ValidateSet('off', 'tunnel', 'system_proxy', 'unknown')][string]$VpnState = 'unknown',
    [ValidateSet('Loopback', 'LAN', 'Internet', 'Unknown')][string]$NetworkLabel = 'Unknown',
    [ValidateSet('pass', 'fail', 'blocked', 'not_run', 'partial')][string]$Result = 'not_run',
    [string]$Notes,
    [string]$OutputDirectory,
    [string]$SkseLog,
    [switch]$IncludeGameLogs
)
$ErrorActionPreference = 'Stop'
$roleName = $Role.ToLowerInvariant()
$repoDirectory = Split-Path -Parent $PSScriptRoot
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $repoDirectory 'reports' }
if (-not (Test-Path -LiteralPath (Join-Path $GameDirectory 'mods') -PathType Container)) {
    throw 'Укажите каталог Minecraft с папками mods, config и logs (не корень Prism Launcher).'
}
$gamePath = (Resolve-Path -LiteralPath $GameDirectory).Path
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$outputRoot = (Resolve-Path -LiteralPath $OutputDirectory).Path
$reportName = '{0}-{1}-{2}-{3}' -f $RunId, $roleName, [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'), ([Guid]::NewGuid().ToString('N').Substring(0, 6))
$reportPath = Join-Path $outputRoot $reportName
New-Item -ItemType Directory -Path $reportPath | Out-Null
$collectorErrors = New-Object 'System.Collections.Generic.List[object]'
$warnings = New-Object 'System.Collections.Generic.List[string]'

function Save-Json([string]$name, $value) {
    ConvertTo-Json -InputObject $value -Depth 10 | Set-Content -LiteralPath (Join-Path $reportPath $name) -Encoding UTF8
}
function Collect-Section([string]$name, [scriptblock]$action) {
    try { Save-Json ($name + '.json') @(& $action) }
    catch {
        $collectorErrors.Add([pscustomobject]@{Section = $name; ErrorType = $_.Exception.GetType().Name; HResult = $_.Exception.HResult})
    }
}
function Safe-Endpoint([string]$value) {
    if ([string]::IsNullOrWhiteSpace($value)) { return $null }
    try {
        $candidate = $value.Trim()
        if ($candidate -notmatch '^[A-Za-z][A-Za-z0-9+.-]*://') { $candidate = 'tcp://' + $candidate }
        $uri = [Uri]$candidate
        if (-not $uri.IsAbsoluteUri -or -not $uri.Host) { throw 'Invalid endpoint' }
        return [pscustomobject]@{Scheme = $uri.Scheme; Host = $uri.DnsSafeHost; Port = $uri.Port; Configured = $true}
    } catch { return [pscustomobject]@{Configured = $true; Parsed = $false} }
}
function Export-SafeCsv([string]$name, $rows, [string[]]$columns) {
    $safeRows = @($rows | ForEach-Object {
        $item = [ordered]@{}
        foreach ($column in $columns) {
            $value = $_.$column
            if ($value -is [string] -and $value -match '^[=+@\-\t\r]') { $value = "'" + $value }
            $item[$column] = $value
        }
        [pscustomobject]$item
    })
    $path = Join-Path $reportPath $name
    if ($safeRows.Count -gt 0) { $safeRows | Export-Csv -LiteralPath $path -NoTypeInformation -Encoding UTF8 -Delimiter ';' }
    else { ('"' + ($columns -join '";"') + '"') | Set-Content -LiteralPath $path -Encoding UTF8 }
}

Write-Host "Сбор отчёта: $RunId / $roleName. Настройки сети не меняются."
$settings = @{}
$configFile = Join-Path $gamePath 'config\skycraft.properties'
if (Test-Path -LiteralPath $configFile) {
    foreach ($line in Get-Content -LiteralPath $configFile -Encoding UTF8) {
        if ($line -match '^\s*([^#!\s][^=]*?)\s*=\s*(.*)$') { $settings[$matches[1].Trim()] = $matches[2].Trim() }
    }
}
$mode = 'DIRECT'
if ($settings.ContainsKey('network.mode')) {
    $configuredMode = $settings['network.mode'].ToUpperInvariant()
    if ($configuredMode -in @('DIRECT', 'SOCKS5', 'HTTP_CONNECT')) { $mode = $configuredMode }
    else { $mode = 'INVALID' }
}
$proxyAddress = $settings['network.proxy']
if (-not $settings.ContainsKey('network.proxy')) {
    if ($mode -eq 'SOCKS5') { $proxyAddress = '127.0.0.1:1080' }
    elseif ($mode -eq 'HTTP_CONNECT') { $proxyAddress = '127.0.0.1:8080' }
}
$proxyEndpoint = Safe-Endpoint $proxyAddress
$configSummary = [ordered]@{
    Mode = $mode
    Proxy = $proxyEndpoint
    ProxyCredentialsSet = (-not [string]::IsNullOrWhiteSpace($settings['network.proxy.username']) -or -not [string]::IsNullOrWhiteSpace($settings['network.proxy.password']))
    Join = (Safe-Endpoint $settings['join'])
    AdvertisedAddress = (Safe-Endpoint $settings['network.advertisedAddress'])
    HostPort = $settings['network.hostPort']
    TimeoutMillis = $settings['network.timeoutMillis']
    Source = 'Allowlist; original properties file is not copied'
}
# Numeric properties are constrained rather than echoed from arbitrary input.
foreach ($numericKey in @('HostPort', 'TimeoutMillis')) {
    $parsedNumber = 0
    if (-not [int]::TryParse([string]$configSummary[$numericKey], [ref]$parsedNumber)) { $configSummary[$numericKey] = $null }
    else { $configSummary[$numericKey] = $parsedNumber }
}
Save-Json 'skycraft-settings.json' $configSummary
$targetEndpoint = ''
if ($TargetHost) {
    $targetName = $TargetHost.Trim('[', ']')
    $targetEndpoint = if ($targetName.Contains(':')) { '[' + $targetName + ']:' + $TargetPort } else { $targetName + ':' + $TargetPort }
}
Save-Json 'run.json' ([ordered]@{
    Schema = 1; RunId = $RunId; Role = $roleName; CollectedUtc = [DateTime]::UtcNow.ToString('o')
    LocalUtcOffset = [DateTimeOffset]::Now.Offset.ToString(); TimeZone = [TimeZoneInfo]::Local.Id
    Network = $NetworkLabel; Target = (Safe-Endpoint $targetEndpoint)
    Zapret = $ZapretState; Cloudflare = $CloudflareState; Vpn = $VpnState; Result = $Result; Notes = $Notes
    StateSource = 'Tester annotations; process/adapter presence does not prove a VPN is active'
    FullGameLogsIncluded = [bool]$IncludeGameLogs
    WindowsVersion = [Environment]::OSVersion.Version.ToString(); Is64BitOS = [Environment]::Is64BitOperatingSystem
})

Collect-Section 'adapters' { Get-NetAdapter | Select-Object Name, InterfaceDescription, Status, LinkSpeed, ifIndex }
Collect-Section 'ip-addresses' { Get-NetIPAddress | Select-Object InterfaceAlias, InterfaceIndex, AddressFamily, IPAddress, PrefixLength, AddressState, PrefixOrigin }
Collect-Section 'dns-servers' { Get-DnsClientServerAddress | Select-Object InterfaceAlias, InterfaceIndex, AddressFamily, ServerAddresses }
Collect-Section 'interfaces' { Get-NetIPInterface | Select-Object InterfaceAlias, InterfaceIndex, AddressFamily, ConnectionState, NlMtu, InterfaceMetric }
Collect-Section 'default-routes' {
    Get-NetRoute | Where-Object DestinationPrefix -in @('0.0.0.0/0', '::/0', '0.0.0.0/1', '128.0.0.0/1', '::/1', '8000::/1') |
        Select-Object InterfaceAlias, InterfaceIndex, DestinationPrefix, NextHop, RouteMetric, State
}
Collect-Section 'process-presence-hints' {
    Get-Process | Where-Object ProcessName -match '^(javaw?|SkyrimSE|skse64_loader|winws2?|warp-svc|CloudflareWARP|sing-box|mihomo|xray|v2ray|openvpn|wireguard)$' |
        Select-Object Id, ProcessName
}
Collect-Section 'system-proxy' {
    $proxyRegistry = Get-ItemProperty -LiteralPath 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
    $endpoints = @()
    foreach ($part in ([string]$proxyRegistry.ProxyServer -split ';')) {
        $address = ($part -split '=', 2)[-1]
        if ($address) { $endpoints += Safe-Endpoint $address }
    }
    $environmentProxies = @()
    foreach ($variable in @('HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY')) {
        $value = [Environment]::GetEnvironmentVariable($variable, 'Process')
        if ($value) { $environmentProxies += [pscustomobject]@{Name = $variable; Endpoint = (Safe-Endpoint $value)} }
    }
    [pscustomobject]@{
        WinInetEnabled = [bool]$proxyRegistry.ProxyEnable; WinInetEndpoints = $endpoints
        PacConfigured = [bool]$proxyRegistry.AutoConfigURL; PacServer = (Safe-Endpoint $proxyRegistry.AutoConfigURL)
        EnvironmentProxies = $environmentProxies
    }
}
Collect-Section 'mod-versions' {
    Get-ChildItem -LiteralPath (Join-Path $gamePath 'mods') -File |
        Where-Object Name -match '^(skycraft-|fabric-api-|e4mc-).*\.jar$' | ForEach-Object {
            [pscustomobject]@{File = $_.Name; Size = $_.Length; Sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
        }
    $packFile = Join-Path (Split-Path -Parent $gamePath) 'mmc-pack.json'
    if (Test-Path -LiteralPath $packFile) {
        (Get-Content -LiteralPath $packFile -Raw -Encoding UTF8 | ConvertFrom-Json).components |
            Select-Object uid, version
    }
}

if ($TargetHost) {
    $targetName = $TargetHost.Trim('[', ']')
    $ipLiteral = $null
    if ([System.Net.IPAddress]::TryParse($targetName, [ref]$ipLiteral)) {
        Collect-Section 'target-route' { Find-NetRoute -RemoteIPAddress $ipLiteral.IPAddressToString | Select-Object InterfaceAlias, InterfaceIndex, IPAddress, DestinationPrefix, NextHop, RouteMetric }
    } else {
        if ($targetName -notmatch '^[A-Za-z0-9.-]{1,253}$') { throw 'TargetHost: используйте IP или обычное DNS-имя без схемы, пути и порта.' }
        Collect-Section 'target-dns' { Resolve-DnsName -Name $targetName -Type A_AAAA -DnsOnly -QuickTimeout | Select-Object Name, Type, IPAddress }
        $warnings.Add('Локальная DNS-проверка вспомогательная: в SOCKS5/HTTP_CONNECT имя цели разрешает прокси, поэтому локальный DNS-отказ сам по себе не означает отказ игры.')
    }
}

$ports = @($TargetPort)
if ($proxyEndpoint -and $proxyEndpoint.Port -gt 0) { $ports += $proxyEndpoint.Port }
if ($configSummary.HostPort -gt 0 -and $configSummary.HostPort -le 65535) { $ports += $configSummary.HostPort }
$tcpRows = New-Object 'System.Collections.Generic.List[object]'
$watch = [Diagnostics.Stopwatch]::StartNew()
do {
    try {
        $stamp = [DateTime]::UtcNow.ToString('o')
        $connections = Get-NetTCPConnection | Where-Object { $_.LocalPort -in $ports -or $_.RemotePort -in $ports }
        foreach ($connection in $connections) {
            $processName = $null
            try { $processName = (Get-Process -Id $connection.OwningProcess -ErrorAction Stop).ProcessName } catch { }
            $tcpRows.Add([pscustomobject]@{
                utc = $stamp; state = [string]$connection.State; pid = $connection.OwningProcess; process = $processName
                local_address = $connection.LocalAddress; local_port = $connection.LocalPort
                remote_address = $connection.RemoteAddress; remote_port = $connection.RemotePort
            })
        }
    } catch { $collectorErrors.Add([pscustomobject]@{Section = 'tcp-sampling'; ErrorType = $_.Exception.GetType().Name; HResult = $_.Exception.HResult}); break }
    if ($WatchSeconds -gt $watch.Elapsed.TotalSeconds) {
        Write-Progress -Activity "Сбор TCP: $RunId / $roleName" -Status 'Можно проводить тест в игре' -PercentComplete ([Math]::Min(99, 100 * $watch.Elapsed.TotalSeconds / $WatchSeconds))
        Start-Sleep -Seconds ([Math]::Min(5, [Math]::Max(1, $WatchSeconds - [int]$watch.Elapsed.TotalSeconds)))
    }
} while ($watch.Elapsed.TotalSeconds -lt $WatchSeconds)
Write-Progress -Activity "Сбор TCP: $RunId / $roleName" -Completed
Export-SafeCsv 'tcp-samples.csv' $tcpRows @('utc', 'state', 'pid', 'process', 'local_address', 'local_port', 'remote_address', 'remote_port')

$traceDirectory = Join-Path $gamePath 'logs\skycraft-network'
$traceFiles = @()
if (Test-Path -LiteralPath $traceDirectory) { $traceFiles = @(Get-ChildItem -LiteralPath $traceDirectory -File -Filter "$RunId-$roleName-*.jsonl*") }
$traceEvents = New-Object 'System.Collections.Generic.List[object]'
$invalidLines = 0
if ($traceFiles.Count -eq 0) { $warnings.Add('Нет лога с нужным ID/ролью. В игре выполните /skycraft debug start ID host или client. Этот отчёт всё равно содержит снимок Windows.') }
else {
    $traceOutput = Join-Path $reportPath 'traces'; New-Item -ItemType Directory -Path $traceOutput | Out-Null
    foreach ($file in $traceFiles) {
        try {
            Copy-Item -LiteralPath $file.FullName -Destination $traceOutput
            foreach ($line in Get-Content -LiteralPath (Join-Path $traceOutput $file.Name) -Encoding UTF8) {
                try {
                    $event = $line | ConvertFrom-Json
                    if ($event.run_id -eq $RunId -and $event.role -eq $roleName) { $traceEvents.Add($event) }
                } catch { $invalidLines++ }
            }
        } catch { $collectorErrors.Add([pscustomobject]@{Section = 'trace-copy'; ErrorType = $_.Exception.GetType().Name; HResult = $_.Exception.HResult}) }
    }
}
$sortedEvents = @($traceEvents | Sort-Object utc, seq)
Export-SafeCsv 'events.csv' $sortedEvents @('utc', 'since_start_ms', 'seq', 'run_id', 'role', 'event', 'attempt_id', 'connection_id', 'phase', 'result', 'mode', 'target_host', 'target_port', 'peer_host', 'peer_port', 'status_code', 'exception_type', 'elapsed_ms', 'ping_ms', 'upload_bytes', 'download_bytes', 'skyrim_linked', 'marker')
$pingSamples = @($traceEvents | Where-Object { $_.event -eq 'sample' -and $null -ne $_.ping_ms } | ForEach-Object { [int]$_.ping_ms })
$joinSamples = @($traceEvents | Where-Object { $_.event -eq 'world_join' -and $_.result -eq 'remote' } | ForEach-Object { $_.elapsed_ms })
$summary = [ordered]@{
    RunId = $RunId; Role = $roleName; Result = $Result; TraceFiles = $traceFiles.Count; Events = $traceEvents.Count
    InvalidOrPartialLines = $invalidLines; TraceStopped = [bool]($traceEvents | Where-Object event -eq 'trace_closed')
    PingSamples = $pingSamples.Count; PingMeanMs = $null; PingMaxMs = $null; RemoteJoinElapsedMs = $joinSamples
    TcpSamples = $tcpRows.Count; CollectionErrors = $collectorErrors.Count
    DroppedTraceEvents = ($traceEvents | Where-Object event -eq 'trace_closed' | Measure-Object -Property dropped_events -Sum).Sum
    Interpretation = 'No automatic PASS: a TCP connection or ping sample does not prove gameplay synchronization'
}
if ($pingSamples.Count -gt 0) { $summary.PingMeanMs = [Math]::Round(($pingSamples | Measure-Object -Average).Average, 1); $summary.PingMaxMs = ($pingSamples | Measure-Object -Maximum).Maximum }
if ($invalidLines -gt 0 -or ($traceFiles.Count -gt 0 -and -not $summary.TraceStopped)) { $warnings.Add('Лог мог ещё записываться. Для окончательного отчёта выполните debug stop, подождите 2 секунды и повторите сбор с WatchSeconds=0.') }
Save-Json 'summary.json' $summary
Save-Json 'collection-errors.json' @($collectorErrors.ToArray())
Save-Json 'warnings.json' @($warnings.ToArray())
if ($IncludeGameLogs) {
    $gameLog = Join-Path $gamePath 'logs\latest.log'
    if (Test-Path -LiteralPath $gameLog) { Copy-Item -LiteralPath $gameLog -Destination (Join-Path $reportPath 'minecraft-latest.log') }
    if (-not $SkseLog) { $SkseLog = Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'My Games\Skyrim Special Edition\SKSE\SkyCraft.log' }
    if (Test-Path -LiteralPath $SkseLog) { Copy-Item -LiteralPath $SkseLog -Destination (Join-Path $reportPath 'SkyCraft-SKSE.log') }
}
@"
SkyCraft — локальный сетевой отчёт
Прогон: $RunId / $roleName. Результат указан тестировщиком: $Result.
summary.json — сводка; events.csv — события мода; tcp-samples.csv — сокеты Windows.
traces — исходные JSONL логи; collection-errors.json — недоступные проверки.
Архив содержит IP-адреса, названия адаптеров, маршруты и ручные заметки.
Пароли прокси, токены и игровые пакеты автоматически не собираются.
Полные игровые логи включены: $([bool]$IncludeGameLogs). Они могут содержать чат и имена.
Ничего не отправлялось в Интернет; DNS-запрос к заданной цели возможен.
Присутствие процесса/адаптера не доказывает, что VPN или фильтр был активен.
Успех TCP не является проверкой совместной игры. В таблице заполните наблюдения обоих игроков.
"@ | Set-Content -LiteralPath (Join-Path $reportPath 'README.txt') -Encoding UTF8
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archivePath = Join-Path $outputRoot ($reportName + '.zip')
[System.IO.Compression.ZipFile]::CreateFromDirectory($reportPath, $archivePath)
Write-Host "Готово: $archivePath"
Write-Host "Событий: $($traceEvents.Count); ошибки сбора: $($collectorErrors.Count). Архив остаётся на компьютере."
