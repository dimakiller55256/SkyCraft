"""Generate the Russian test workbook and semicolon CSV templates. No macros."""
import csv
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / ".tools/table-python"))
from openpyxl import Workbook, load_workbook
from openpyxl.comments import Comment
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.worksheet.datavalidation import DataValidation
from openpyxl.worksheet.table import Table, TableStyleInfo
from openpyxl.formatting.rule import FormulaRule

OUT = ROOT / "docs/testing"
NAVY, TEAL, PALE, INK = "17324D", "087F8C", "E8F4F6", "213547"


def csv_file(name, headers, rows):
    with (OUT / name).open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.writer(stream, delimiter=";")
        writer.writerow(headers)
        writer.writerows(rows)


def page(workbook, name, headers, rows, widths, table_name):
    sheet = workbook.create_sheet(name)
    sheet.append(headers)
    for row in rows:
        sheet.append(row)
    sheet.freeze_panes = "C2" if name in ("Матрица", "Прогоны") else "A2"
    sheet.sheet_view.showGridLines = False
    for col, width in enumerate(widths, 1):
        sheet.column_dimensions[sheet.cell(1, col).column_letter].width = width
    for cell in sheet[1]:
        cell.fill = PatternFill("solid", fgColor=NAVY)
        cell.font = Font(name="Calibri", bold=True, color="FFFFFF", size=11)
        cell.alignment = Alignment(wrap_text=True, vertical="center")
    sheet.row_dimensions[1].height = 32
    for row in sheet.iter_rows(min_row=2):
        for cell in row:
            cell.font = Font(name="Calibri", size=11, color=INK)
            cell.alignment = Alignment(wrap_text=True, vertical="top")
        sheet.row_dimensions[row[0].row].height = 78 if name != "Прогоны" else 64
    table = Table(displayName=table_name, ref=sheet.dimensions)
    table.tableStyleInfo = TableStyleInfo(name="TableStyleMedium2", showRowStripes=True)
    sheet.add_table(table)
    sheet.sheet_properties.pageSetUpPr.fitToPage = True
    sheet.page_setup.orientation = "landscape"
    sheet.page_setup.paperSize = sheet.PAPERSIZE_A3 if len(headers) > 7 else sheet.PAPERSIZE_A4
    sheet.page_setup.fitToWidth, sheet.page_setup.fitToHeight = 1, 0
    sheet.print_title_rows = "1:1"
    sheet.print_options.horizontalCentered = True
    sheet.oddFooter.center.text = "SkyCraft YS | &P / &N"
    return sheet


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    scenarios = json.loads((OUT / "scenarios.json").read_text(encoding="utf-8"))
    workbook = Workbook()
    start = workbook.active
    start.title = "Начать"
    start.sheet_view.showGridLines = False
    start.merge_cells("A1:F2")
    start["A1"] = "SkyCraft YS · сетевые тесты"
    start["A1"].font = Font(name="Calibri", size=24, bold=True, color="FFFFFF")
    start["A1"].fill = PatternFill("solid", fgColor=NAVY)
    start["A1"].alignment = Alignment(vertical="center")
    start.row_dimensions[1].height = 30
    for letter in "ABCDEF":
        start.column_dimensions[letter].width = 20
    intro = [
        "Сборка 0.1.2-ys.network.2 · Minecraft 26.3 · Fabric Loader 0.19.5",
        "1. Сейчас один ПК: S01–S03. Друг доступен: L01 (LAN) или I01 (Интернет).",
        "2. На обоих ПК один Run ID, разные роли: /skycraft debug start L01_01 host (или client).",
        "3. Откройте вкладку «Шаги». Настоящий адрес: из общей LAN или доступный внешний адрес/порт.",
        "4. После debug stop подождите 2 с; collect-network-report.cmd соберёт ZIP каждого ПК.",
        "5. В «Прогоны» впишите наблюдения и значения summary.json; результат выбираете сами.",
        "PASS = выполнены ожидания сценария. FAIL = ожидание нарушилось. BLOCKED = нет условий.",
        "PARTIAL = часть проверки. NOT_RUN = не запускали. TCP и local smoke не доказывают совместную игру.",
        "Прокси указывается явно в skycraft.properties. DIRECT следует маршрутам Windows.",
        "В архиве есть IP/адаптеры/метки; секреты автоматически не собираются. Не вводите их в Notes.",
        "Интеграция STR/квесты/предметы ещё не реализованы; таблица проверяет Minecraft-сеть.",
    ]
    for number, text in enumerate(intro, 4):
        start.merge_cells(start_row=number, start_column=1, end_row=number, end_column=6)
        cell = start.cell(number, 1, text)
        cell.font = Font(name="Calibri", size=12, color=INK)
        cell.alignment = Alignment(wrap_text=True, vertical="center")
        cell.fill = PatternFill("solid", fgColor=PALE if number % 2 == 0 else "FFFFFF")
        start.row_dimensions[number].height = 36
    for index, sheet_name in enumerate(("Шаги", "Матрица", "Прогоны", "Откуда данные", "Разбор сбоя"), 17):
        start.cell(index, 1, f"Открыть: {sheet_name}").hyperlink = f"#'{sheet_name}'!A1"
        start.cell(index, 1).font = Font(color=TEAL, underline="single", size=12)
    start.merge_cells("A23:F24")
    start["A23"] = "Подробная инструкция: YS-TESTING.md в наборе / docs/YS-TESTING.md в репозитории.\n25 сценариев; для разных режимов и адресов — новый Run ID."
    start["A23"].alignment = Alignment(wrap_text=True)
    start.freeze_panes = "A4"
    steps = [
        ["0", "Подготовка", "Копия экземпляра, тестовый мир, одна версия JAR", "То же", "Версии и настройки обоих ПК"],
        ["1", "Начать лог", "/skycraft debug start L01_01 host", "/skycraft debug start L01_01 client", "Один ID. /skycraft debug status без ошибки записи"],
        ["2", "Открыть", "/skycraft host 25565\n/skycraft debug mark HOST_READY\nПередать доступный клиенту адрес", "Дождаться HOST_READY; подставить реальный адрес вместо примера ниже", "Порт и IP/DNS; UTC начала"],
        ["3", "TCP", "Оставаться в мире", "/skycraft netcheck 192.168.1.20:25565", "tcp_ready или точное сообщение отказа; это не ping"],
        ["4", "Войти", "Дождаться клиента", "/join 192.168.1.20:25565\n/skycraft debug mark JOIN_SEEN", "world_join remote, elapsed_ms; /join не выполнять в свой хост из того же экземпляра"],
        ["5", "Движение", "/skycraft debug mark PEER_SEEN\nПройти 10 блоков; наблюдать клиента", "Наблюдать хоста; пройти 10 блоков", "Оба видят движение, записать задержки/откаты"],
        ["6", "Блоки", "Поставить/сломать 3 блока; увидеть изменения клиента", "Увидеть изменения хоста; поставить/сломать свои 3 блока", "После успеха у обоих: /skycraft debug mark BLOCK_BOTH_OK; иначе BLOCK_FAIL + описание"],
        ["7", "Стабильность", "Играть указанное время", "Играть указанное время", "Минуты факт, обрывы; ping из summary.json"],
        ["8", "Повторный вход", "Не закрывать мир", "/skycraft debug mark LEAVE_BEGIN\n/leave\n/skycraft debug mark OWN_WORLD_OK\n/join тот-же-адрес:порт", "Свой мир после leave; повторный вход; сохранённый блок"],
        ["9", "Закончить", "/skycraft debug mark RUN_END\n/skycraft debug stop", "Те же команды", "Подождать 2 секунды; если падение — сохранить незавершённый лог"],
        ["10", "Отчёт", "collect-network-report.cmd → Host", "collect-network-report.cmd → Client", "Тот же ID; два ZIP в reports; заполнить «Прогоны»"],
        ["!", "Вход провалился", "Сохранить свои наблюдения", "После возврата в свой мир: mark JOIN_FAIL; debug stop", "Не ставить PASS совместной игре; точный текст/UTC + ZIP"],
    ]
    page(workbook, "Шаги", ["№", "Этап", "Хост", "Клиент", "Что записать"], steps, [6, 20, 55, 60, 64], "Steps")
    matrix_headers = ["ID", "Когда", "Сценарий", "Транспорт", "Что делает хост", "Что делает клиент", "Условия", "Ожидаемый результат", "Минуты", "Ограничение"]
    matrix_rows = [[s[key] for key in ("id", "stage", "name", "transport", "host", "client", "conditions", "expected", "minutes", "limit")] for s in scenarios]
    matrix = page(workbook, "Матрица", matrix_headers, matrix_rows, [8, 25, 34, 22, 52, 62, 48, 57, 10, 48], "Scenarios")
    for row in range(2, matrix.max_row + 1):
        matrix.row_dimensions[row].height = 120
    run_headers = ["Сценарий", "Run ID", "Начало UTC", "Заполнил (роль)", "Условия хоста + версии/профили", "Условия клиента + версии/профили", "Сеть", "Адрес:порт", "Транспорт", "TCP", "Вход, мс", "Ping сред., мс", "Ping макс., мс", "Минуты факт", "Неожиданные обрывы", "Движение обоим", "Блоки обоим", "Leave / повторный вход", "Результат", "ZIP хоста", "ZIP клиента", "Шаг / UTC / сообщение / заметки"]
    run_rows = []
    for scenario in scenarios:
        for repeat in range(1, 4):
            row = [None] * len(run_headers)
            row[0], row[1], row[18] = scenario["id"], f'{scenario["id"]}_{repeat:02}', "NOT_RUN"
            run_rows.append(row)
    runs = page(workbook, "Прогоны", run_headers, run_rows, [11, 16, 24, 20, 42, 42, 15, 30, 20, 17, 14, 17, 17, 14, 20, 18, 18, 24, 17, 44, 44, 68], "Runs")
    for cell in runs[1]:
        cell.comment = Comment("Одна строка = одна попытка с одинаковым Run ID на обоих ПК. Данные из summary.json переносите с клиентского отчёта. Незаполненное поле не означает успех.", "SkyCraft YS")
    choices = {"D": '"оба,host,client"', "G": '"Loopback,LAN,Internet,Unknown"', "I": '"DIRECT,SOCKS5,HTTP_CONNECT"', "J": '"OK,REFUSED,TIMEOUT,DNS_ERROR,AUTH_ERROR,NOT_RUN"', "P": '"YES,NO,NOT_RUN"', "Q": '"YES,NO,NOT_RUN"', "R": '"OK,FAIL,NOT_RUN"', "S": '"PASS,FAIL,BLOCKED,PARTIAL,NOT_RUN"'}
    for column, formula in choices.items():
        validation = DataValidation(type="list", formula1=formula, allow_blank=True)
        validation.errorTitle, validation.error = "Выберите из списка", "Поле принимает значения из выпадающего списка."
        validation.showErrorMessage = True
        runs.add_data_validation(validation)
        validation.add(f"{column}2:{column}1001")
    for column in ("K", "L", "M", "N", "O"):
        validation = DataValidation(type="decimal", operator="greaterThanOrEqual", formula1=0, allow_blank=True)
        validation.showErrorMessage = True
        runs.add_data_validation(validation)
        validation.add(f"{column}2:{column}1001")
    for result, color in (("PASS", "DDF3E4"), ("FAIL", "FCE4E4"), ("BLOCKED", "FFF0C2"), ("PARTIAL", "DFECFC")):
        runs.conditional_formatting.add(f"S2:S{runs.max_row}", FormulaRule(formula=[f'S2="{result}"'], fill=PatternFill("solid", fgColor=color)))
    for index, result in enumerate(("PASS", "FAIL", "BLOCKED", "PARTIAL", "NOT_RUN"), 27):
        start.cell(index, 1, result)
        start.cell(index, 2, f'=COUNTIF(\'Прогоны\'!S2:S1001,"{result}")')
    start["A26"] = "Заполненные результаты (Excel пересчитает при открытии)"
    source_rows = [
        ["Время/ID", "run.json; JSONL utc", "UTC в формате ISO; Windows timezone отдельно", "Проверить часы обоих ПК; monotonic elapsed не зависит от изменения часов"],
        ["Транспорт/прокси", "skycraft-settings.json, config событие", "DIRECT/SOCKS5/HTTP_CONNECT; host:port", "Пароли и username исключены; наличие credentials — boolean"],
        ["Версии", "mod-versions.json; environment событие", "Версии и SHA256; для двух ПК одинаковый JAR", "Версии zapret/WARP/VPN и профили вписать вручную"],
        ["IP/DNS/маршрут", "ip-addresses/dns-servers/default-routes/target-* JSON", "LAN адрес — активный адаптер общей сети; внешний — роутер/провайдер", "CGNAT автоматически не определяется; remote DNS прокси отдельно"],
        ["Вход, мс", "summary.json RemoteJoinElapsedMs", "Длительность join_requested → world_join remote", "При нескольких входах перенести выбранное значение, остальные указать в Notes"],
        ["Ping", "summary.json PingMeanMs / PingMaxMs", "Значение задержки игрока Minecraft, снято раз в 5 с", "Это не новая RTT-проба каждые 5 с; первые нули возможны"],
        ["TCP", "probe_result; tcp-samples.csv", "Успех TCP/прокси; LISTEN/ESTABLISHED в Windows", "Время netcheck не ping; без WatchSeconds лишь один снимок"],
        ["Трафик", "sample / transport_close upload_bytes/download_bytes", "Прокси-мост, байты; потери пакетов не измеряются", "Для DIRECT счётчиков нет"],
        ["Синхронизация", "Наблюдение обоих + marker", "Движение/блоки, Leave/Join, длительность, обрывы", "Лог не может сам доказать, что блок был виден обоим"],
        ["Полнота", "trace_closed dropped_events; warnings/collection-errors", "Есть конец лога? Есть пропуски/недоступные разделы?", "Сбой сбора не означает сбой мода; результат вручную"],
        ["Архивы", "reports/RunId-role-UTC-random.zip", "Точные имена ZIP хоста/клиента", "Есть IP/пути/метки; ничего не загружается автоматически"],
    ]
    page(workbook, "Откуда данные", ["Поле", "Файл / источник", "Что переносить", "Предел точности"], source_rows, [22, 54, 65, 65], "Sources")
    faults = [
        ["Нет команды", "JAR/Loader/mods", "Тестовый экземпляр? Один SkyCraft JAR? Версия network.2?"],
        ["Нет лога", "debug status; warnings.json", "Точные Run ID/роль и GameDirectory; после debug stop подождать 2 с"],
        ["DNS", "dial_phase; target-dns", "Адрес самого прокси разрешается локально; имя цели через прокси удалённо"],
        ["Отказ TCP", "host_open; LISTEN; dial_phase", "Хост открыт? Порт/адрес доступны с клиента или со стороны прокси?"],
        ["Таймаут", "Последний dial_phase + ZIP обоих", "Различить установление TCP и ожидание ответа прокси; проверить маршрут"],
        ["Авторизация", "socks_auth / HTTP 407", "Проверить секреты локально; в заметки их не вводить"],
        ["HTTP 403", "http_connect_result", "Прокси разрешает CONNECT к нужному порту?"],
        ["TCP OK, вход FAIL", "world_join/channel_error + сообщение игры", "Версии, игровой отказ; полный latest.log добавлять только при необходимости"],
        ["Блоки/движение FAIL", "BLOCK_FAIL/UTC + наблюдения обоих", "Конкретный блок/действие, кто что видел, воспроизводимость"],
        ["Разрыв при смене VPN", "SWITCH_BEFORE/AFTER + снимки маршрутов", "Стабилизировать сеть; ручной повторный вход; TCP при смене маршрута может оборваться"],
        ["Закрытый NAT", "Условия сети/провайдера", "BLOCKED; доступный входящий узел или будущий ретранслятор"],
    ]
    page(workbook, "Разбор сбоя", ["Симптом", "Где смотреть", "Следующее действие"], faults, [29, 54, 96], "Faults")
    workbook.calculation.fullCalcOnLoad = True
    path = OUT / "SkyCraft-Network-Tests.xlsx"
    workbook.save(path)
    csv_file("scenarios.csv", matrix_headers, matrix_rows)
    csv_file("runs-template.csv", run_headers, run_rows)
    check = load_workbook(path)
    assert len(check.worksheets) == 6 and check["Матрица"].max_row == 26
    assert check["Прогоны"].max_row == 76 and check["Прогоны"]["S2"].value == "NOT_RUN"
    assert all(check["Прогоны"].cell(row, 19).value == "NOT_RUN" for row in range(2, 77))
    assert path.stat().st_size > 10000
    print(f"TEST WORKBOOK PASS: {len(scenarios)} scenarios, 75 empty runs, six tabs, dropdowns, filters, CSV; {path}")


if __name__ == "__main__":
    main()
