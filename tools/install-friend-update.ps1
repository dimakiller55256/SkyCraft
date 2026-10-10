#requires -Version 5.1
[CmdletBinding()]
param([string]$GameDirectory, [string]$NativeDll)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'skycraft-package-common.ps1')
if (-not $GameDirectory) {
    $suggested = Find-SkyCraftGameDirectory
    Write-Host 'Закройте Skyrim и экземпляр SkyCraft в Minecraft. Комплект обновляет Minecraft и, при наличии исправлений, SkyCraft.dll в MO2.'
    $GameDirectory = Read-Host "Каталог Minecraft, содержащий mods/config/logs [$suggested]"
    if ([string]::IsNullOrWhiteSpace($GameDirectory)) { $GameDirectory = $suggested }
}
if (-not $GameDirectory) { throw 'Укажите каталог Minecraft через Prism → экземпляр → папка Minecraft.' }
$gamePath = (Resolve-Path -LiteralPath $GameDirectory.Trim('"')).Path
$modsPath = Join-Path $gamePath 'mods'
if (-not (Test-Path -LiteralPath $modsPath -PathType Container)) { throw 'Это не каталог Minecraft с папкой mods.' }
if ((Get-Item -LiteralPath $modsPath).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Папка mods является ссылкой. Используйте её реальное расположение.' }
if (Get-Process -Name SkyrimSE -ErrorAction SilentlyContinue) { throw 'Сначала закройте Skyrim обычным способом.' }
$manifest = Get-SkyCraftPackageManifest
$repoPath = Split-Path -Parent $PSScriptRoot
$nativePath = $null
$nativeTemporary = $null
if ($manifest.nativeDll.changed) {
    $nativeSource = Join-Path $repoPath 'native\SkyCraft.dll'
    if ((Get-SkyCraftFileHash -LiteralPath $nativeSource).Hash -ne $manifest.nativeDll.sha256) { throw 'Новая SkyCraft.dll повреждена. Распакуйте весь комплект заново.' }
    if (-not $NativeDll) {
        Write-Host 'В MO2: слева SkyCraft -> правой кнопкой -> Открыть в проводнике -> SKSE -> Plugins. Нужен полный путь к существующей SkyCraft.dll в этой папке.'
        $NativeDll = Read-Host 'Полный путь к установленной SkyCraft.dll'
    }
    $nativePath = (Resolve-Path -LiteralPath $NativeDll.Trim('"') -ErrorAction Stop).Path
    if ((Split-Path -Leaf $nativePath) -ne 'SkyCraft.dll' -or (Get-Item -LiteralPath $nativePath).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Выберите реальный файл SkyCraft.dll из установленного мода MO2.' }
    $nativeTemporary = $nativePath + '.installing-' + [Guid]::NewGuid().ToString('N')
}
$jarSource = Join-Path $repoPath $manifest.fabricJar.file
if ($manifest.fabricJar.file -ne (Split-Path -Leaf $manifest.fabricJar.file)) { throw 'Unsafe JAR filename in manifest.' }
if ((Get-SkyCraftFileHash -LiteralPath $jarSource -Algorithm SHA256).Hash -ne $manifest.fabricJar.sha256) { throw 'Контрольная сумма нового JAR не совпала. Распакуйте комплект заново.' }
$newMetadata = Read-FabricMetadata $jarSource
if ($newMetadata.id -ne 'skycraft' -or $newMetadata.version -ne $manifest.version) { throw 'Wrong SkyCraft JAR in package.' }
$destination = Join-Path $modsPath $manifest.fabricJar.file
$oldFiles = @()
foreach ($file in Get-ChildItem -LiteralPath $modsPath -File -Filter '*.jar') {
    try { $metadata = Read-FabricMetadata $file.FullName }
    catch { throw "Невозможно прочитать $($file.Name). Закройте Minecraft и проверьте JAR; изменения ещё не выполнены." }
    if ($metadata.id -in @('skycraft', 'e4mc')) { $oldFiles += $file }
}
if ((Test-Path -LiteralPath $destination) -and -not ($oldFiles | Where-Object FullName -eq $destination)) { throw 'Новое имя JAR занято другим файлом; изменения не выполнены.' }
if ($oldFiles.Count -eq 1 -and $oldFiles[0].FullName -eq $destination -and (Get-SkyCraftFileHash -LiteralPath $destination).Hash -eq $manifest.fabricJar.sha256 -and (-not $nativePath -or (Get-SkyCraftFileHash -LiteralPath $nativePath).Hash -eq $manifest.nativeDll.sha256)) {
    Write-Host 'Эта сборка уже установлена; никаких изменений.'
    return
}
$backupRoot = Join-Path (Split-Path -Parent $gamePath) 'SkyCraft-YS-backups'
$backupPath = [IO.Path]::GetFullPath((Join-Path $backupRoot ([DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,6))))
if (-not $backupPath.StartsWith([IO.Path]::GetFullPath($backupRoot) + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe backup destination.' }
$temporary = Join-Path $modsPath ($manifest.fabricJar.file + '.installing-' + [Guid]::NewGuid().ToString('N'))
$moved = New-Object 'System.Collections.Generic.List[object]'
$installed = $false
$nativeInstalled = $false
try {
    Copy-Item -LiteralPath $jarSource -Destination $temporary
    if ((Get-SkyCraftFileHash -LiteralPath $temporary).Hash -ne $manifest.fabricJar.sha256) { throw 'Temporary JAR verification failed.' }
    if ($nativePath) {
        Copy-Item -LiteralPath $nativeSource -Destination $nativeTemporary
        if ((Get-SkyCraftFileHash -LiteralPath $nativeTemporary).Hash -ne $manifest.nativeDll.sha256) { throw 'Temporary DLL verification failed.' }
    }
    New-Item -ItemType Directory -Path $backupPath | Out-Null
    foreach ($file in $oldFiles) {
        if (-not $file.FullName.StartsWith($modsPath + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Old JAR is outside mods.' }
        $backupFile = Join-Path $backupPath $file.Name
        Move-Item -LiteralPath $file.FullName -Destination $backupFile
        $moved.Add([pscustomobject]@{Original=$file.FullName; Backup=$backupFile})
    }
    if ($nativePath) {
        $nativeBackup = Join-Path $backupPath 'SkyCraft.dll'
        Move-Item -LiteralPath $nativePath -Destination $nativeBackup
        $moved.Add([pscustomobject]@{Original=$nativePath; Backup=$nativeBackup})
        Move-Item -LiteralPath $nativeTemporary -Destination $nativePath
        $nativeInstalled = $true
    }
    Move-Item -LiteralPath $temporary -Destination $destination
    $installed = $true
    [pscustomobject]@{Utc=[DateTime]::UtcNow.ToString('o'); Version=$manifest.version; Installed=$destination; Moved=@($moved.ToArray())} |
        ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $backupPath 'installation.json') -Encoding UTF8
} catch {
    if ($installed -and (Test-Path -LiteralPath $destination)) { Remove-Item -LiteralPath $destination }
    if ($nativeInstalled -and (Test-Path -LiteralPath $nativePath)) { Remove-Item -LiteralPath $nativePath }
    foreach ($item in $moved) { if (-not (Test-Path -LiteralPath $item.Original)) { Move-Item -LiteralPath $item.Backup -Destination $item.Original } }
    throw
} finally {
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
    if ($nativeTemporary -and (Test-Path -LiteralPath $nativeTemporary)) { Remove-Item -LiteralPath $nativeTemporary }
}
Write-Host "Установлен SkyCraft $($manifest.version). Старые SkyCraft/e4mc сохранены: $backupPath"
Write-Host 'Fabric API, настройки, аккаунты и миры сохранены. Продолжите по файлу ИГРОВЫЕ-ПРОВЕРКИ.html в распакованном комплекте.'
