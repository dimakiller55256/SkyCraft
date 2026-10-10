#requires -Version 5.1
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$PlanFile)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object Text.UTF8Encoding($false)
$plan = Get-Content -LiteralPath $PlanFile -Raw -Encoding UTF8 | ConvertFrom-Json
if ($plan.action -eq 'install') {
    # An unrelated Java process is never stopped. Inspect command lines only for
    # exact selected instance use; do not write command lines into reports.
    $game = [IO.Path]::GetFullPath([string]$plan.game)
    $gameAlt = $game.Replace('\','/')
    try {
        $java = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction Stop)
    } catch { throw 'Cannot verify that the selected Minecraft instance is closed. Close it and use the existing update installer.' }
    foreach ($process in $java) {
        if ($process.CommandLine -and ($process.CommandLine.IndexOf($game,[StringComparison]::OrdinalIgnoreCase) -ge 0 -or $process.CommandLine.IndexOf($gameAlt,[StringComparison]::OrdinalIgnoreCase) -ge 0)) { throw 'Selected Minecraft instance is still running. Close it normally before updating.' }
    }
    & (Join-Path $PSScriptRoot 'install-friend-update.ps1') -GameDirectory $game -NativeDll ([string]$plan.nativeDll)
} elseif ($plan.action -eq 'environment') {
    & (Join-Path $PSScriptRoot 'collect-test-environment.ps1') -OutputFile $plan.output -TargetAddresses @($plan.targets)
} elseif ($plan.action -eq 'collect') {
    if ($plan.runId -notmatch '^[A-Za-z0-9_-]{1,64}$' -or $plan.role -notin @('host','client') -or $plan.network -notin @('LAN','Internet','Loopback')) { throw 'Invalid collection plan.' }
    & (Join-Path $PSScriptRoot 'check-friend-installation.ps1') -GameDirectory $plan.game -SkyrimDirectory $plan.skyrim -ModsDirectory $plan.mods -OutputDirectory $plan.output
    $parameters = @{GameDirectory=$plan.game; RunId=$plan.runId; Role=$plan.role; NetworkLabel=$plan.network; Result='partial'; Notes='Automatic assistant; per-check results in automatic-results.json. Visual gameplay not automatically validated.'; OutputDirectory=$plan.output}
    if ($plan.traceFile) { $parameters.TraceFileName = [string]$plan.traceFile }
    if ($plan.gameplay) { $parameters.IncludeGameplayDiagnostics = $true }
    if ($plan.startedUtc) { $parameters.StartedUtc = [string]$plan.startedUtc }
    if ($plan.target) {
        $uri = [Uri]('tcp://' + [string]$plan.target)
        $parameters.TargetHost = $uri.DnsSafeHost
        $parameters.TargetPort = $uri.Port
    }
    & (Join-Path $PSScriptRoot 'collect-network-report.ps1') @parameters
} else { throw 'Invalid worker action.' }
