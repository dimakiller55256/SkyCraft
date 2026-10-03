# Shared by the friend installer and read-only installation check.
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Read-FabricMetadata([string]$path) {
    $archive = [IO.Compression.ZipFile]::OpenRead($path)
    try {
        $entry = $archive.GetEntry('fabric.mod.json')
        if (-not $entry) { return $null }
        if ($entry.Length -gt 1048576) { throw 'fabric.mod.json is too large' }
        $reader = New-Object IO.StreamReader($entry.Open())
        try { return $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    } finally { $archive.Dispose() }
}
function Get-SkyCraftPackageManifest {
    $manifestPath = Join-Path (Split-Path -Parent $PSScriptRoot) 'package-manifest.json'
    if (-not (Test-Path -LiteralPath $manifestPath)) { throw 'package-manifest.json is missing; use the unpacked friend kit.' }
    return Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
}
function Find-SkyCraftGameDirectory {
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA 'SkyCraft\Prism\instances\SkyCraft\.minecraft'),
        (Join-Path $env:APPDATA 'PrismLauncher\instances\SkyCraft\.minecraft'),
        (Join-Path $env:APPDATA 'PrismLauncher\instances\SkyCraft\minecraft'),
        'D:\PrismLauncher\instances\SkyCraft\minecraft',
        'D:\PrismLauncher\instances\SkyCraft\.minecraft'
    )
    return $candidates | Where-Object { Test-Path -LiteralPath (Join-Path $_ 'mods') -PathType Container } | Select-Object -First 1
}
