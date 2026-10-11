#requires -Version 5.1
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$OutputFile,[string[]]$TargetAddresses=@())
$ErrorActionPreference='Stop'
$failures=New-Object 'System.Collections.Generic.List[object]'
function Read-Fact([string]$section,[scriptblock]$read) {
    try { return @(& $read) } catch { $failures.Add([pscustomobject]@{section=$section; error=$_.Exception.GetType().Name});return @() }
}
function Safe-WinwsArguments([string]$line) {
    # Only port/protocol/strategy switches, never whole command lines, file paths or host lists.
    $values=New-Object 'System.Collections.Generic.List[string]'
    foreach($match in [regex]::Matches($line,'--(?:wf-tcp|wf-udp|filter-tcp|filter-udp|filter-l3|filter-l7|dpi-desync(?:-[a-z0-9-]+)?|new|wf-raw(?:-part)?|hostlist(?:-[a-z0-9-]+)?|ipset(?:-[a-z0-9-]+)?)(?:=(?:"[^"]*"|[^\s]+))?')) {
        $arg=$match.Value
        if($arg -match '^--(?:hostlist|ipset|wf-raw)' -or $arg -match '[@\\/]' -or $arg.Length -gt 180) { $arg=($arg -split '=',2)[0]+'=[configured]' }
        $values.Add($arg)
    }
    return $values.ToArray()
}
$processes=Read-Fact 'processes' {Get-CimInstance Win32_Process | Where-Object {$_.Name -match '^(winws2?|warp-svc|warp-cli|Cloudflare WARP|RadminVPN|Outline|sing-box|xray|v2ray|hiddify|nekoray|clash|mihomo|openvpn|wireguard)(\.exe)?$'} | ForEach-Object {
    $version=$null
    $filters=@()
    if($_.Name -match '^winws') {$filters=@(Safe-WinwsArguments $_.CommandLine)}
    if($_.ExecutablePath -and (Test-Path -LiteralPath $_.ExecutablePath)) {$version=(Get-Item -LiteralPath $_.ExecutablePath).VersionInfo.FileVersion}
    [pscustomobject]@{name=$_.Name; pid=$_.ProcessId; version=$version; winwsFilters=$filters}
}}
$adapters=Read-Fact 'adapters' {Get-NetAdapter | Select-Object Name,InterfaceDescription,Status,ifIndex}
$routes=Read-Fact 'routes' {Get-NetRoute | Select-Object InterfaceIndex,InterfaceAlias,DestinationPrefix,NextHop,RouteMetric}
$ips=Read-Fact 'addresses' {Get-NetIPAddress | Select-Object InterfaceIndex,InterfaceAlias,IPAddress,AddressFamily,PrefixLength}
$dns=Read-Fact 'dns' {Get-DnsClientServerAddress | Select-Object InterfaceIndex,InterfaceAlias,AddressFamily,ServerAddresses}
$listeners=Read-Fact 'listeners' {Get-NetTCPConnection -State Listen | Select-Object LocalAddress,LocalPort,OwningProcess}
$selectedRoutes=Read-Fact 'selected-routes' {
    foreach($address in ($TargetAddresses | Select-Object -Unique)) {
        $parsed=$null
        if(-not [Net.IPAddress]::TryParse($address,[ref]$parsed)) {continue}
        try {[pscustomobject]@{target=$parsed.ToString();selection=@(Find-NetRoute -RemoteIPAddress $parsed.ToString() -ErrorAction Stop | Select-Object InterfaceIndex,InterfaceAlias,IPAddress,DestinationPrefix,NextHop,RouteMetric)}}
        catch {[pscustomobject]@{target=$parsed.ToString();error=$_.Exception.GetType().Name}}
    }
}
$drivers=Read-Fact 'drivers' {Get-CimInstance Win32_SystemDriver | Where-Object {$_.Name -match 'WinDivert|warp|Radmin'} | Select-Object Name,State,StartMode}
$warp= [ordered]@{available=$false; status=@(); settings=@()}
$warpCommand=Get-Command warp-cli.exe -ErrorAction SilentlyContinue
$warpPath=if($warpCommand){$warpCommand.Source}else{Join-Path $env:ProgramFiles 'Cloudflare\Cloudflare WARP\warp-cli.exe'}
if(Test-Path -LiteralPath $warpPath) {
    $warp.available=$true
    foreach($verb in @('status','settings')) {
        try {
            $start=New-Object Diagnostics.ProcessStartInfo
            $start.FileName=$warpPath;$start.Arguments=$verb;$start.UseShellExecute=$false;$start.CreateNoWindow=$true;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
            $child=[Diagnostics.Process]::Start($start)
            $readOut=$child.StandardOutput.ReadToEndAsync();$readErr=$child.StandardError.ReadToEndAsync()
            if(-not $child.WaitForExit(4000)) {$child.Kill();throw 'WARP read timed out'}
            $lines=$readOut.GetAwaiter().GetResult() -split '\r?\n'
            # Account, organization, device IDs and enrollment data are excluded.
            $warp[$verb]=@($lines | Where-Object {$_ -match '(?i)^(?:\s*\([^)]*\)\s*)?(?:\s*(?:Status update|Status|Reason|Success|Service mode|Mode|WARP tunnel protocol|Tunnel protocol|Proxy port|Connection status|Resolve via|MASQUE Protocol Settings)\s*[:=].*|\s*(?:Connected|Disconnected|Connecting)\s*)$'} | ForEach-Object {$_.Substring(0,[Math]::Min($_.Length,200))})
            $child.Dispose()
        } catch {$failures.Add([pscustomobject]@{section=('warp-'+$verb);error=$_.Exception.GetType().Name})}
    }
}
$result=[ordered]@{schema=1;utc=[DateTime]::UtcNow.ToString('o');processes=$processes;adapters=$adapters;addresses=$ips;routes=$routes;selectedRoutes=$selectedRoutes;dns=$dns;listeners=$listeners;drivers=$drivers;warp=$warp;collectionErrors=$failures.ToArray();interpretation='Read-only observations. Process presence alone does not prove active filtering. WARP is not an incoming multiplayer relay. No network settings were changed.'}
$parent=Split-Path -Parent ([IO.Path]::GetFullPath($OutputFile))
New-Item -ItemType Directory -Path $parent -Force | Out-Null
$result | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $OutputFile -Encoding UTF8
