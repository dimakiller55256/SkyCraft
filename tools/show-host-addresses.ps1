#requires -Version 5.1
$ErrorActionPreference = 'Stop'
Write-Host 'Локальные адреса хоста. Сеть и брандмауэр не изменяются; запросов в Интернет нет.'
$rows = foreach ($configuration in Get-NetIPConfiguration -All) {
    $adapter = Get-NetAdapter -InterfaceIndex $configuration.InterfaceIndex -ErrorAction SilentlyContinue
    if ($adapter.Status -ne 'Up') { continue }
    [pscustomobject]@{
        Interface = $configuration.InterfaceAlias
        Physical = $adapter.HardwareInterface
        IPv4 = ($configuration.IPv4Address.IPAddress -join ', ')
        Router = ($configuration.IPv4DefaultGateway.NextHop -join ', ')
        IPv6 = ($configuration.IPv6Address.IPAddress -join ', ')
    }
}
$rows | Format-Table -Wrap -AutoSize
Write-Host 'LAN: выберите IPv4 подключённого Ethernet/Wi-Fi (Physical=True), к которому подключён и клиент. Добавьте :25565.'
Write-Host 'Интернет IPv4: нужен WAN IPv4 из интерфейса роутера и проброс TCP-порта на этот LAN IPv4. Локальный IPv4 клиенту из другой сети не подходит.'
Write-Host 'Router — адрес веб-интерфейса роутера в этой LAN, не адрес для /join из Интернета.'
Write-Host 'IPv6: для Интернета нужен глобальный адрес и проверка TCP с ПК друга. Не используйте fe80::, fc/fd или ::1.'
Write-Host 'Подробно: вкладка Адрес хоста в таблице или docs/YS-TESTING.md.'
