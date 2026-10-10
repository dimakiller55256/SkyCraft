#requires -Version 5.1
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path -Parent $PSScriptRoot
$local = Get-Content -LiteralPath (Join-Path $workspaceRoot '.tools\dev-local.json') -Raw | ConvertFrom-Json
$env:JAVA_HOME = $local.javaHome
$env:GRADLE_USER_HOME = $local.gradleUserHome
$env:TEMP = Join-Path $workspaceRoot '.tools\tmp'
$env:TMP = $env:TEMP
# Normalize duplicate PATH/Path entries in the inherited environment of this
# process only. PowerShell 5 Start-Process otherwise rejects this Windows setup.
$testPath = [Environment]::GetEnvironmentVariable('Path','Process')
[Environment]::SetEnvironmentVariable('PATH',$null,'Process')
[Environment]::SetEnvironmentVariable('Path',$null,'Process')
[Environment]::SetEnvironmentVariable('Path',$testPath,'Process')
$hostGame = Join-Path $workspaceRoot '.tools\easylan-smoke-host'
$guestGame = Join-Path $workspaceRoot '.tools\easylan-smoke-client'
$audit = Join-Path $workspaceRoot ('dist\easylan-validation-' + (Get-Date -Format yyyyMMdd-HHmmss))
New-Item -ItemType Directory -Path $audit -Force | Out-Null
foreach ($gameDir in @($hostGame,$guestGame)) {
    New-Item -ItemType Directory -Path $gameDir -Force | Out-Null
    foreach ($name in @('smoke-result.txt','port.txt')) {
        $old = Join-Path $gameDir $name
        if (Test-Path -LiteralPath $old) { Remove-Item -LiteralPath $old }
    }
    Set-Content -LiteralPath (Join-Path $gameDir 'options.txt') -Encoding UTF8 -Value @('lang:ru_ru','guiScale:2','onboardAccessibility:false','soundCategory_master:0.0','renderDistance:3','simulationDistance:5','maxFps:30','pauseOnLostFocus:false')
}
Push-Location -LiteralPath $workspaceRoot
try {
    & .\easylan\gradlew.bat -p easylan build exportSmokeLaunch -PlanSmoke '-Dorg.gradle.jvmargs=-Xmx512m -XX:ActiveProcessorCount=2' --max-workers=1 --no-daemon --no-configuration-cache --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Cannot build EasyLAN smoke clients.' }
    & $local.python (Join-Path $PSScriptRoot 'run-easylan-clients.py') $audit
    if ($LASTEXITCODE -ne 0) { throw "EasyLAN clients failed; inspect $audit" }
} finally { Pop-Location }
