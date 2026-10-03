[CmdletBinding()]
param(
    [ValidateSet('Check', 'BuildFabric', 'BuildSkse', 'NetworkSmoke')]
    [string]$Action = 'Check',
    [string]$JavaHome,
    [string]$CMakePath,
    [string]$GradleUserHome
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$configPath = Join-Path $repoRoot '.tools\dev-local.json'
$localConfig = @{}
if (Test-Path -LiteralPath $configPath) {
    $localConfig = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
}
if (-not $JavaHome) { $JavaHome = $localConfig.javaHome }
if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if (-not $GradleUserHome) { $GradleUserHome = $localConfig.gradleUserHome }
if (-not $GradleUserHome) { $GradleUserHome = Join-Path $env:LOCALAPPDATA 'SkyCraft\Gradle' }
$GradleUserHome = [IO.Path]::GetFullPath($GradleUserHome)
# Skyrim recursively scans Mods during startup. Build caches can exceed its
# 260-byte path buffer, even when the addon is disabled in MO2.
$cacheAncestor = $GradleUserHome
$cacheInsideGame = $false
while ($cacheAncestor) {
    if (Test-Path -LiteralPath (Join-Path $cacheAncestor 'SkyrimSE.exe')) {
        $cacheInsideGame = $true
        break
    }
    $cacheParent = Split-Path -Parent $cacheAncestor
    if ($cacheParent -eq $cacheAncestor) { break }
    $cacheAncestor = $cacheParent
}
if (-not $CMakePath) { $CMakePath = $localConfig.cmakePath }
if (-not $CMakePath) {
    $cmakeCommand = Get-Command cmake -ErrorAction SilentlyContinue
    if ($cmakeCommand) { $CMakePath = $cmakeCommand.Source }
}

$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
$vsRoot = $null
if (Test-Path -LiteralPath $vswhere) {
    $vsRoot = & $vswhere -latest -products '*' -version '[18.0,19.0)' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
}
if (-not $CMakePath -and $vsRoot) {
    $candidate = Join-Path $vsRoot 'Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe'
    if (Test-Path -LiteralPath $candidate) { $CMakePath = $candidate }
}
$jdkReady = $JavaHome -and (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\javac.exe'))
$cmakeReady = $CMakePath -and (Test-Path -LiteralPath $CMakePath)
$commonLibReady = Test-Path -LiteralPath (Join-Path $repoRoot 'skse\extern\CommonLibSSE-NG\CMakeLists.txt')
$vcpkgReady = Test-Path -LiteralPath (Join-Path $repoRoot '.tools\vcpkg\vcpkg.exe')

if ($Action -eq 'Check') {
    [pscustomobject]@{
        Repository = $repoRoot
        JavaHome = $JavaHome
        GradleUserHome = $GradleUserHome
        GradleCacheOutsideSkyrim = -not $cacheInsideGame
        JdkPresent = [bool]$jdkReady
        VisualStudio2026Cpp = $vsRoot
        CMake = $CMakePath
        CommonLibPresent = $commonLibReady
        VcpkgPresent = $vcpkgReady
        AutomaticDeployment = 'Disabled by BuildSkse'
    } | Format-List
    if ($jdkReady) { & (Join-Path $JavaHome 'bin\javac.exe') --version }
    return
}

if ($Action -eq 'BuildFabric' -or $Action -eq 'NetworkSmoke') {
    if (-not $jdkReady) { throw 'Set -JavaHome to a JDK 25 folder, or configure .tools/dev-local.json.' }
    if ($cacheInsideGame) { throw 'Gradle cache must be outside the Skyrim game directory. Set -GradleUserHome or .tools/dev-local.json.' }
    $savedEnvironment = @{}
    foreach ($name in @('JAVA_HOME', 'GRADLE_USER_HOME', 'TEMP', 'TMP')) {
        $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
    $tmpPath = Join-Path $repoRoot '.tools\tmp'
    New-Item -ItemType Directory -Path $tmpPath -Force | Out-Null
    try {
        $env:JAVA_HOME = $JavaHome
        $env:GRADLE_USER_HOME = $GradleUserHome
        # The desktop environment's default TEMP failed Java's AF_UNIX loopback.
        # This process-local directory lets Gradle establish its local connection.
        $env:TEMP = $tmpPath
        $env:TMP = $tmpPath
        Push-Location (Join-Path $repoRoot 'fabric')
        try {
            if ($Action -eq 'NetworkSmoke') {
                $smokeResult = Join-Path $repoRoot '.tools\network-smoke-game\network-smoke-result.txt'
                if (Test-Path -LiteralPath $smokeResult) { Remove-Item -LiteralPath $smokeResult }
                & .\gradlew.bat -PnetworkSmoke runNetworkSmokeClient --no-daemon --no-configuration-cache --console=plain
                if (-not (Test-Path -LiteralPath $smokeResult) -or
                    (Get-Content -LiteralPath $smokeResult -Raw).Trim() -ne 'PASS') {
                    throw 'Minecraft networking smoke check failed; inspect .tools/network-smoke-game/logs/latest.log.'
                }
                $traceDir = Join-Path $repoRoot '.tools\network-smoke-game\logs\skycraft-network'
                $traceFile = Get-ChildItem -LiteralPath $traceDir -Filter 'AUTO-auto-*.jsonl' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
                if (-not $traceFile) { throw 'Networking smoke test did not create its diagnostic log.' }
                $traceEvents = Get-Content -LiteralPath $traceFile.FullName -Encoding UTF8 | ForEach-Object { $_ | ConvertFrom-Json }
                if (-not ($traceEvents | Where-Object event -eq 'channel_active') -or
                    -not ($traceEvents | Where-Object { $_.event -eq 'dial_phase' -and $_.phase -eq 'http_connect_result' -and $_.status_code -eq 200 }) -or
                    -not ($traceEvents | Where-Object event -eq 'trace_closed')) {
                    throw 'Networking smoke test did not record channel, proxy and logger-shutdown events.'
                }
            } else {
                & .\gradlew.bat build --no-daemon --no-configuration-cache --console=plain
            }
            if ($LASTEXITCODE -ne 0) { throw "Fabric build failed (exit $LASTEXITCODE)." }
        } finally { Pop-Location }
    } finally {
        foreach ($name in $savedEnvironment.Keys) {
            [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
        }
    }
    return
}

if (-not $vsRoot) { throw 'Install Visual Studio 2026 Build Tools with C++ x64/x86 and a Windows SDK.' }
if (-not $cmakeReady) { throw 'CMake is missing. Install the C++ CMake tools component or pass -CMakePath.' }
if (-not $commonLibReady) { throw 'Run git submodule update --init --recursive.' }
if (-not $vcpkgReady) { throw 'Clone microsoft/vcpkg into .tools/vcpkg and run bootstrap-vcpkg.bat -disableMetrics.' }
Push-Location (Join-Path $repoRoot 'skse')
try {
    & $CMakePath --preset default '-DSKYCRAFT_DEPLOY_DIR='
    if ($LASTEXITCODE -ne 0) { throw "CMake configuration failed (exit $LASTEXITCODE)." }
    & $CMakePath --build --preset release
    if ($LASTEXITCODE -ne 0) { throw "SKSE build failed (exit $LASTEXITCODE)." }
} finally { Pop-Location }
