#requires -Version 5.1
# Catch direct Actor calls with a missing AE relocation in pinned CommonLib.
# This is a source audit, not proof that nonzero addresses work at runtime.
[CmdletBinding()]
param([string]$PluginSource)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
if (-not $PluginSource) { $PluginSource = Join-Path $repo 'skse\src' }
$common = Join-Path $repo 'skse\extern\CommonLibSSE-NG\src\RE\A\Actor.cpp'
$missing = @{}
$header = '(?m)^\s*[\w:<>,*& ]+\s+(?<qualified>(?:\w+::)+\w+)\([^;{}]*\)(?:\s+const)?\s*\{'
foreach ($file in Get-Item -LiteralPath $common) {
    $source = [IO.File]::ReadAllText($file.FullName)
    foreach ($match in [regex]::Matches($source, $header)) {
        $begin = $match.Index + $match.Length
        $depth = 1; $end = $begin
        while ($end -lt $source.Length -and $depth -gt 0) {
            if ($source[$end] -eq '{') { $depth++ }
            elseif ($source[$end] -eq '}') { $depth-- }
            $end++
        }
        $body = $source.Substring($begin, $end - $begin)
        if ($body -match 'RELOCATION_ID\(\s*\d+\s*,\s*0\s*\)') {
            $qualified = $match.Groups['qualified'].Value
            $missing[$qualified.Split(':')[-1]] = $qualified
        }
    }
}
if (-not $missing.ContainsKey('IsEssentialDown')) { throw 'CommonLib audit did not identify the known missing AE function; update the scanner.' }
$failures = @()
foreach ($file in Get-ChildItem -LiteralPath $PluginSource -Recurse -File | Where-Object { $_.Extension -in '.cpp','.h' }) {
    $lineNumber = 0
    foreach ($line in [IO.File]::ReadAllLines($file.FullName)) {
        $lineNumber++
        $code = ($line -split '//', 2)[0]
        foreach ($name in $missing.Keys) {
            if ($code -match ('(?:->|\.|::)\s*' + [regex]::Escape($name) + '\s*\(')) {
                $failures += "$($file.FullName):${lineNumber}: $($missing[$name]) has AE relocation 0"
            }
        }
    }
}
if ($failures.Count) { throw ($failures -join [Environment]::NewLine) }
Write-Host "PASS: no direct calls to $($missing.Count) CommonLib Actor functions with a missing AE relocation. Runtime testing remains required."
