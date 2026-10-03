# Подготовка SkyCraft YS

База: [chasmlol/SkyCraft](https://github.com/chasmlol/SkyCraft), тег `v0.1.2`,
commit `bfcaf178524b92c2cdeb88e4ce0f13ef9ded6f32`.
Ветка подготовки: `ys/setup`. `upstream` указывает на оригинал;
`origin` — на [dimakiller55256/SkyCraft](https://github.com/dimakiller55256/SkyCraft).

## Сборка

Команды выполнять в корне этого Git-репозитория (`source/` в рабочей папке).

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\dev.ps1 -Action Check
powershell -NoProfile -ExecutionPolicy Bypass -File tools\dev.ps1 -Action BuildFabric
powershell -NoProfile -ExecutionPolicy Bypass -File tools\dev.ps1 -Action BuildSkse
```

Java выбирается через `-JavaHome`, игнорируемый `.tools/dev-local.json`
или `JAVA_HOME`. Пример локального конфига:

```json
{
  "javaHome": "D:\\path\\to\\jdk-25",
  "cmakePath": ""
}
```

Текущая установка Prism уже содержит Microsoft OpenJDK 25.0.1. Путь к нему
сохранён только в локальном конфиге. Gradle 9.7.1 загружается штатным wrapper;
его кэш находится в `.tools/gradle-user-home`. Скрипт задаёт временную папку
`.tools/tmp` на время сборки: это устранило наблюдавшуюся ошибку Java
`Unable to establish loopback connection` / `UnixDomainSockets.connect0`.
Причина сбоя в стандартной TEMP глубже не установлена.

Нужные C++-компоненты перечислены в `tools/buildtools.vsconfig`:
Visual Studio 2026 Build Tools, MSVC x64/x86, Windows SDK и CMake.
Менеджер зависимостей расположен в `.tools/vcpkg`; он подготовлен командой
`bootstrap-vcpkg.bat -disableMetrics`. Подмодули закреплены исходным коммитом.

Проверенные версии окружения 2026-10-03:

| Компонент | Версия |
|---|---|
| Skyrim runtime | 1.7.104.0 |
| SKSE64 | 2.3.1; присутствует Address Library для 1.7.104.0 |
| Minecraft / Fabric Loader | 26.3 / 0.19.5 |
| JDK | Microsoft OpenJDK 25.0.1 |
| Gradle / разрешённый Loom | 9.7.1 / 1.18.2 |
| Visual Studio Build Tools | 2026, 18.10.3 |
| MSVC / CMake | 14.51.36231 / 4.3.1-msvc1 |
| Git / Git LFS / GitHub CLI | 2.53.0 / 3.7.1 / 2.102.0 |
| vcpkg checkout | 3cbc1db4d867ec83c89fba4c461321c11f78b5e3 |
| CommonLib | d61bca4de789428aa7d98a770b1323ddf1bb855c |
| OpenVR | 60eb187801956ad277f1cae6680e3a410ee0873b |

В upstream Loom задан как SNAPSHOT, а vcpkg не закреплён manifest baseline.
При воспроизведении на другой машине сверять эти версии; текущая запись
фиксирует проверенное окружение, но не является полным lock-файлом.

`BuildSkse` явно отключает автоматическое копирование DLL в игру.
Результаты: `fabric/build/libs/skycraft-0.1.2.jar` и, после успешной C++-сборки,
`skse/build/RelWithDebInfo/SkyCraft.dll` и PDB. Для первого теста нужны
отдельные сохранение Skyrim и мир Minecraft. Сборка сама по себе не проверяет
поведение в игре.

## GitHub и навыки

GitHub-коннектор и локальная авторизация Git — отдельные механизмы.
Переносимый GitHub CLI находится в `.tools/gh/bin/gh.exe`; вход выполняется
через штатный `gh auth login --web`, без токенов в файлах проекта.
Автор коммитов настроен локально, с noreply-адресом GitHub.

Навык `skycraft-modding` установлен в пользовательский каталог Codex.
Его поддерживаемая копия: `tools/skills/skycraft-modding/SKILL.md`.
Уже доступны навыки `systematic-debugging`, `code-reviewer`,
`gh-fix-ci`, `gh-address-comments`; устанавливать их дубликаты не требуется.

Новые возможности описаны в [YS-ROADMAP.md](YS-ROADMAP.md).

## Подтверждённые проверки

- Исходная Java/Fabric-часть 0.1.2: сборка успешна.
- Исходная C++/SKSE-часть 0.1.2: полная сборка `RelWithDebInfo` успешна;
  созданы `SkyCraft.dll` и `SkyCraft.pdb`. Автоматическое копирование в игру
  отключено, значение `SKYCRAFT_DEPLOY_DIR` в CMakeCache пустое.
- Существующие JUnit-тесты: 21 пройден, 0 ошибок/падений.
- Новые сценарии сети и конвертации пока не реализованы и не проверены.
- Навык прошёл `quick_validate.py`; PowerShell-скрипт прошёл разбор синтаксиса
  и запуск всех трёх действий: `Check`, `BuildFabric`, `BuildSkse`.
- Сохранились предупреждения исходного проекта: Java/Gradle deprecations
  и C4459 о затенении `scene` в `WorldRender.cpp:2913`; сборку они не прерывают.
- Запуск собранной версии в игре в рамках подготовки не выполнялся.

При изменении окружения повторять соответствующую сборку. Успех сборки
подтверждает работоспособность инструментов, но не игровую совместимость
будущих изменений.
