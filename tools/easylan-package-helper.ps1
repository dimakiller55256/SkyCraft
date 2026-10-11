#requires -Version 5.1
[CmdletBinding()]
param([ValidateSet('Install','Report')][string]$Action='Install',[string]$GameDirectory,[string]$PackageDirectory,[switch]$NonInteractive)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.IO.Compression.FileSystem
if(-not $PackageDirectory) { $PackageDirectory=Split-Path -Parent $PSScriptRoot }
function Read-Mod([string]$Path) {
    $z=$null
    try {
        $z=[IO.Compression.ZipFile]::OpenRead($Path);$entry=$z.GetEntry('fabric.mod.json')
        if(-not $entry) { return $null }
        $r=New-Object IO.StreamReader($entry.Open())
        try { return ($r.ReadToEnd()|ConvertFrom-Json) } finally { $r.Dispose() }
    } catch { return $null } finally { if($z){$z.Dispose()} }
}
try {
    if(-not $GameDirectory) {
        $dialog=New-Object Windows.Forms.FolderBrowserDialog
        $dialog.Description='Выберите папку Minecraft нужного экземпляра (с mods, config, logs), не саму mods.'
        $dialog.ShowNewFolderButton=$false
        if($dialog.ShowDialog() -ne [Windows.Forms.DialogResult]::OK) { exit 0 }
        $GameDirectory=$dialog.SelectedPath
    }
    $game=(Resolve-Path -LiteralPath $GameDirectory).Path
    if(-not (Test-Path -LiteralPath (Join-Path $game 'mods')) -or -not ((Test-Path -LiteralPath (Join-Path $game 'config')) -or (Test-Path -LiteralPath (Join-Path $game 'logs')))) {
        throw 'Выбрана не папка Minecraft с Fabric. В Prism: правая кнопка по экземпляру → Папка Minecraft.'
    }
    if($Action -eq 'Report') {
        $reports=Join-Path $game 'easylan-reports'
        New-Item -ItemType Directory -Path $reports -Force | Out-Null
        $id='EasyLAN-Windows-'+(Get-Date -Format yyyyMMdd-HHmmss)+'-'+[Guid]::NewGuid().ToString('N').Substring(0,6)
        $temp=Join-Path $reports ($id+'-files');New-Item -ItemType Directory -Path $temp | Out-Null
        $info=[ordered]@{schema=1;timeUtc=[DateTime]::UtcNow.ToString('o');os=[Environment]::OSVersion.VersionString;eventsCollected=$false;adapters=@();mods=@()}
        try { $info.adapters=@(Get-NetIPAddress -AddressFamily IPv4 | Select-Object InterfaceAlias,IPAddress,PrefixLength,AddressState) } catch { $info.adapterError=$_.Exception.GetType().Name }
        $info.mods=@(Get-ChildItem -LiteralPath (Join-Path $game 'mods') -File -Filter '*.jar' | ForEach-Object {
            $meta=Read-Mod $_.FullName
            if($meta) { [ordered]@{id=$meta.id;version=$meta.version} }
        })
        $info | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $temp 'environment.json') -Encoding UTF8
        $latest=Get-ChildItem -LiteralPath $reports -Filter '*-events.jsonl' -File | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if($latest) { Copy-Item -LiteralPath $latest.FullName -Destination (Join-Path $temp 'events.jsonl');$info.eventsCollected=$true }
        $cfg=Join-Path $game 'config\easylan.cfg'
        if(Test-Path -LiteralPath $cfg) { Copy-Item -LiteralPath $cfg -Destination (Join-Path $temp 'easylan.cfg') }
        $info | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $temp 'environment.json') -Encoding UTF8
        $zip=Join-Path $reports ($id+'.zip');[IO.Compression.ZipFile]::CreateFromDirectory($temp,$zip)
        if(-not $NonInteractive) {
            [Windows.Forms.Clipboard]::SetText($zip)
            [Windows.Forms.MessageBox]::Show("Отчёт сохранён. Путь скопирован:`r`n$zip",'EasyLAN') | Out-Null
        }
        Write-Output $zip
        exit 0
    }
    $escapedGame=$game.Replace('\','/').ToLowerInvariant()
    try {
        $running=@(Get-CimInstance Win32_Process | Where-Object {
            $_.Name -in @('java.exe','javaw.exe') -and $_.CommandLine -and $_.CommandLine.Replace('\','/').ToLowerInvariant().Contains($escapedGame)
        })
        if($running.Count) { throw 'Minecraft этого экземпляра работает. Закройте его и повторите установку.' }
    } catch [Microsoft.Management.Infrastructure.CimException] { throw 'Не удалось проверить работающий Minecraft. Закройте игры; используйте ручную установку из инструкции.' }
    $manifest=Get-Content -LiteralPath (Join-Path $PackageDirectory 'package-manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach($file in $manifest.mods) {
        $path=Join-Path $PackageDirectory $file.file
        if((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $file.sha256) { throw ('Неверная контрольная сумма: '+$file.file) }
        $meta=Read-Mod $path
        if(-not $meta -or $meta.id -ne $file.id -or $meta.version -ne $file.version) { throw ('Неверный мод: '+$file.file) }
    }
    $stamp=(Get-Date -Format yyyyMMdd-HHmmss)+'-'+[Guid]::NewGuid().ToString('N').Substring(0,6)
    $backup=Join-Path $game ('EasyLAN-backups\'+$stamp)
    New-Item -ItemType Directory -Path $backup -Force | Out-Null
    $moved=@();$installed=@()
    try {
        foreach($old in @(Get-ChildItem -LiteralPath (Join-Path $game 'mods') -File -Filter '*.jar')) {
            $meta=Read-Mod $old.FullName
            if($meta -and $meta.id -in @('easylan','fabric-api')) {
                $to=Join-Path $backup $old.Name; Move-Item -LiteralPath $old.FullName -Destination $to
                $moved+=@{source=$old.FullName;backup=$to}
            }
        }
        foreach($file in $manifest.mods) {
            $dest=Join-Path $game ('mods\'+[IO.Path]::GetFileName($file.file))
            if(Test-Path -LiteralPath $dest) { throw ('Файл назначения уже существует и не распознан как заменяемый мод: '+$dest) }
            Copy-Item -LiteralPath (Join-Path $PackageDirectory $file.file) -Destination $dest
            $installed+=$dest
        }
    } catch {
        foreach($dest in $installed) { if(Test-Path -LiteralPath $dest) { Remove-Item -LiteralPath $dest } }
        foreach($old in $moved) { Move-Item -LiteralPath $old.backup -Destination $old.source }
        throw
    }
    if(-not $NonInteractive) { [Windows.Forms.MessageBox]::Show("EasyLAN для Minecraft 26.3 установлен.`r`nРезервная копия: $backup`r`nЗапустите Minecraft → одиночный мир → Esc → EasyLAN: LAN.",'EasyLAN') | Out-Null }
    Write-Output ('Installed EasyLAN; backup: '+$backup)
} catch {
    if(-not $NonInteractive) { [Windows.Forms.MessageBox]::Show($_.Exception.Message,'EasyLAN: ошибка') | Out-Null }
    Write-Error $_.Exception.Message
    exit 1
}
