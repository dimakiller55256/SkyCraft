# zapret и Cloudflare в SkyCraft

Исследовано 4 октября 2026. [zapret](https://github.com/bol-van/zapret)
применяет стратегии рассинхронизации DPI к выбранным пакетам. Windows winws
использует WinDivert; wf-tcp/udp задаёт перехват, filter-tcp/udp/l3/l7 и списки
IP/доменов выбирают профиль. [Документация разработчика](https://github.com/bol-van/zapret/blob/master/docs/readme.md).

[Flowseal zapret-discord-youtube](https://github.com/Flowseal/zapret-discord-youtube)
содержит стратегии и служебные скрипты. В [general ALT](https://github.com/Flowseal/zapret-discord-youtube/blob/main/general%20%28ALT%29.bat)
game filter подставляется в перехват и отдельные профили неизвестного протокола.
Название ALT недостаточно: помощник сохраняет безопасные аргументы реально
запущенного winws, в порядке профилей. Файлы списков и их пути не копирует.

На ПК пользователя при проверке перехват TCP 1024–65535 включал 25565.
Это **возможность обработки**, не установленная причина обрыва: ещё учитываются
IP-списки/исключения. На момент снимка warp-cli сообщил Disconnected, несмотря
на запущенный warp-svc; процесс не равнозначен подключённому туннелю.

Cloudflare публикует [BoringTun](https://github.com/cloudflare/boringtun), компонент
WireGuard. Это не исходники всего Windows One Client. Для установленного клиента
используем официальные [режимы One Client](https://developers.cloudflare.com/cloudflare-one/team-and-resources/devices/cloudflare-one-client/configure/modes/)
и читаемые status/settings. Полный туннель, DNS-only и Local proxy различаются;
локальный прокси требует реального порта приложения. SkyCraft использует явные
SOCKS5/CONNECT, DIRECT следует маршрутам ОС. Адрес выхода WARP не обеспечивает
входящий игровой сервер на компьютере пользователя.

[Split Tunnels](https://developers.cloudflare.com/cloudflare-one/team-and-resources/devices/cloudflare-one-client/configure/route-traffic/split-tunnels/)
задаёт включение/исключение IP-маршрутов. Туннель может изменить путь к Radmin.
Помощник снимает выбранный Windows маршрут, DNS и WARP до/после; сам не меняет
exclusions по одному наличию адаптера. Сначала требуется подтвердить неверный путь.

Адаптация network.4: Radmin IPv4 первым, WARP не подменяет общую виртуальную сеть,
выбор транспорта явный, проверка прокси до хоста, таймаут 10 секунд, раздельные
TCP/login/configuration/play, сохранение причины, ID/версия принимающего сервера.
Локальные проверки под запущенными фильтрами подтверждают локальный цикл;
межсетевую совместимость проверяет пара ПК в тех же реальных условиях.
