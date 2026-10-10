# Compatibility entry point: the network kit is now the complete friend kit.
# BuildFabric and the verified upstream SkyCraft-0.1.2.zip cache are required.
# No output directory is cleared and no game files are installed.
#requires -Version 5.1
[CmdletBinding()]
param([string]$PythonPath = 'python', [string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$arguments = @((Join-Path $PSScriptRoot 'package-friend-kit.py'))
if ($OutputDirectory) { $arguments += @('--output-directory', $OutputDirectory) }
& $PythonPath @arguments
if ($LASTEXITCODE -ne 0) { throw 'Friend kit packaging failed; inspect the diagnostic output above.' }
