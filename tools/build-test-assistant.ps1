#requires -Version 5.1
[CmdletBinding()]
param([string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
if (-not $OutputDirectory) { $OutputDirectory = Join-Path (Split-Path -Parent $PSScriptRoot) '.tools\assistant-build' }
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path -LiteralPath $compiler)) { throw 'Missing .NET Framework 4 C# compiler.' }
$source = Join-Path $PSScriptRoot 'assistant\SkyCraftTestAssistant.cs'
$destination = Join-Path $OutputDirectory 'SkyCraft-Test-Assistant.exe'
& $compiler /nologo /target:winexe /platform:anycpu /optimize+ /utf8output ("/out:$destination") /reference:System.Windows.Forms.dll /reference:System.Drawing.dll /reference:System.Web.Extensions.dll /reference:System.IO.Compression.dll /reference:System.IO.Compression.FileSystem.dll $source
if ($LASTEXITCODE -ne 0) { throw 'Assistant build failed.' }
Write-Host "Built: $destination"
