#requires -Version 5.1
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$versionLine = Get-Content -LiteralPath (Join-Path $repoRoot 'fabric\gradle.properties') | Where-Object { $_ -match '^version\s*=' } | Select-Object -First 1
if (-not $versionLine) { throw 'version is missing from gradle.properties.' }
$version = ($versionLine -split '=', 2)[1].Trim()
if ($version -notmatch '^[A-Za-z0-9._-]+$') { throw 'Invalid Fabric version' }
$jar = Join-Path $repoRoot "fabric\build\libs\skycraft-$version.jar"
if (-not (Test-Path -LiteralPath $jar)) { throw 'BuildFabric is required before packaging.' }
$distPath = Join-Path $repoRoot 'dist'
$staging = Join-Path $repoRoot ('.tools\test-kit-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $distPath, $staging, (Join-Path $staging 'docs\testing'), (Join-Path $staging 'tools') -Force | Out-Null
$jarName = "skycraft-fabric-$version.jar"
Copy-Item -LiteralPath $jar -Destination (Join-Path $distPath $jarName)
Copy-Item -LiteralPath $jar -Destination (Join-Path $staging $jarName)
Copy-Item -LiteralPath (Join-Path $repoRoot 'docs\YS-TESTING.md') -Destination (Join-Path $staging 'docs')
Copy-Item -LiteralPath (Join-Path $repoRoot 'docs\YS-NETWORK.md') -Destination (Join-Path $staging 'docs')
foreach ($name in @('SkyCraft-Network-Tests.xlsx', 'scenarios.csv', 'runs-template.csv')) {
    Copy-Item -LiteralPath (Join-Path $repoRoot ('docs\testing\' + $name)) -Destination (Join-Path $staging 'docs\testing')
}
foreach ($name in @('collect-network-report.ps1', 'collect-network-report.cmd', 'test-report-wizard.ps1')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $staging 'tools')
}
@"
SkyCraft YS $version — набор сетевых тестов
1. Прочитайте docs/YS-TESTING.md: установка JAR в копию экземпляра Minecraft, команды и адреса.
2. Откройте docs/testing/SkyCraft-Network-Tests.xlsx (Excel или LibreOffice); сейчас доступны S01–S03 на одном ПК.
3. В игре: /skycraft debug start L01_01 host (либо client). После теста: /skycraft debug stop.
4. Подождите 2 секунды. Запустите tools/collect-network-report.cmd. Отчёты сохраняются в reports рядом с tools.
JAR: $jarName. Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.161.0+26.3.
В mods должен быть ровно один SkyCraft JAR. Старый SkyCraft/e4mc сохраните вне mods. В MO2 этот JAR не устанавливается.
SKSE 0.1.2 остаётся совместим по формату протокола; совместная игра и ваши сочетания VPN ещё требуют проверки.
Набор не требует нового аккаунта. Ретранслятор для закрытого NAT пока не реализован.
ZIP-отчёты содержат IP/адаптеры/маршруты и ваши метки; конфиги, пароли, файлы аккаунтов и пакеты автоматически не копируются.
Ничего автоматически не устанавливается в игру и не загружается в Интернет.
"@ | Set-Content -LiteralPath (Join-Path $staging 'НАЧАТЬ.txt') -Encoding UTF8
Get-FileHash -LiteralPath (Join-Path $staging $jarName) -Algorithm SHA256 | ForEach-Object {
    ($_.Hash + '  ' + $jarName) | Set-Content -LiteralPath (Join-Path $staging 'SHA256.txt') -Encoding ASCII
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = Join-Path $distPath "SkyCraft-YS-network-test-kit-$version.zip"
if (Test-Path -LiteralPath $archive) { throw "Archive already exists: $archive. Preserve or rename it before packaging again." }
[IO.Compression.ZipFile]::CreateFromDirectory($staging, $archive)
Write-Host "Набор тестов: $archive"
Get-FileHash -LiteralPath $archive, (Join-Path $distPath $jarName) -Algorithm SHA256 | Select-Object Path, Hash
