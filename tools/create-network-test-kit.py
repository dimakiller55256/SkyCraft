"""Generate detailed Russian workbook, standalone HTML, Markdown and CSV; no macros."""
import csv
import html
import json
import math
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / '.tools/table-python'))
from openpyxl import Workbook, load_workbook
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.worksheet.datavalidation import DataValidation
from openpyxl.worksheet.table import Table, TableStyleInfo
from openpyxl.formatting.rule import FormulaRule
from network_test_guide import PREPARATION, ADDRESSES, TRANSPORTS, STEPS, DATA, FAILURES, SCENARIO_SETUP, NEGATIVE, SOURCES

OUT = ROOT / 'docs/testing'
NAVY, INK = '17324D', '213547'

def csv_file(name, headers, rows):
    with (OUT / name).open('w', encoding='utf-8-sig', newline='') as file:
        writer = csv.writer(file, delimiter=';'); writer.writerow(headers); writer.writerows(rows)

def page(book, name, headers, rows, widths, table_name):
    sheet = book.create_sheet(name); sheet.append(headers)
    for row in rows: sheet.append(row)
    sheet.freeze_panes = 'C2' if name in ('Матрица','Прогоны','Сценарии подробно') else 'A2'
    sheet.sheet_view.showGridLines = False
    for col, width in enumerate(widths, 1): sheet.column_dimensions[sheet.cell(1,col).column_letter].width = width
    for cell in sheet[1]:
        cell.fill = PatternFill('solid', fgColor=NAVY)
        cell.font = Font(name='Calibri', bold=True, color='FFFFFF', size=11)
        cell.alignment = Alignment(wrap_text=True, vertical='center')
    sheet.row_dimensions[1].height = 36
    for row in sheet.iter_rows(min_row=2):
        lines = 1
        for index, cell in enumerate(row):
            cell.font = Font(name='Calibri', size=11, color=INK)
            cell.alignment = Alignment(wrap_text=True, vertical='top')
            lines = max(lines, sum(max(1, math.ceil(len(part) / max(4, widths[index] - 4))) for part in str(cell.value or '').split('\n')))
        sheet.row_dimensions[row[0].row].height = min(409, max(42, lines * 15 + 14))
    table = Table(displayName=table_name, ref=sheet.dimensions)
    table.tableStyleInfo = TableStyleInfo(name='TableStyleMedium2',showRowStripes=True)
    sheet.add_table(table)
    sheet.sheet_properties.pageSetUpPr.fitToPage = True
    sheet.page_setup.orientation = 'landscape'; sheet.page_setup.paperSize = sheet.PAPERSIZE_A3
    sheet.page_setup.fitToWidth = 1; sheet.page_setup.fitToHeight = 0
    sheet.print_title_rows = '1:1'; sheet.oddFooter.center.text = 'SkyCraft YS | &P / &N'
    return sheet

def scenario_rows(scenarios):
    result = []
    for s in scenarios:
        sid = s['id']
        if sid in NEGATIVE:
            for index, (title, host, client, expected) in enumerate(NEGATIVE[sid],1):
                result.append([sid,str(index),title,host,client,expected])
            result.append([sid,'Итог','Отчёт','Для одного ПК роль берите из описания шага; для двух — Host. debug stop, пауза 2 с, Собрать отчёт.cmd режим 2.', 'Точный ID '+sid+'_01, Client; если другая попытка — новый номер. Запишите настройки до/после и восстановите изменённый config.', s['expected']+' Требуемые минуты: '+str(s['minutes'])+'.'])
            continue
        condition, action = SCENARIO_SETUP[sid]
        result.append([sid,'0','Условия до прогона',condition,action,'Проверить prerequisites. При отсутствии инфраструктуры BLOCKED с причиной; не начинать общий поток вслепую.'])
        for number,title,host,client,expected in STEPS:
            if number == 'X1': continue
            host = host.replace('RUN_ID',sid+'_01').replace('I01_01',sid+'_01')
            client = client.replace('RUN_ID',sid+'_01').replace('I01_01',sid+'_01')
            if number == '8':
                host += f' Для {sid}: минимум {s["minutes"]} минут.'
                client += f' Для {sid}: минимум {s["minutes"]} минут.'
            result.append([sid,number,title,host,client,expected])
        result.append([sid,'Итог','Критерий сценария',s['expected'],s['limit'],'Неизвестные шаги = PARTIAL. Точное нарушение ожидания = FAIL.'])
    return result

def markdown_and_html(scenarios):
    intro = ('Сборка 0.1.2-ys.network.2: Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.161.0+26.3, Java 25. '
             'Проверенный Skyrim runtime 1.7.104.0, SKSE 2.3.1. У владельца проверены запуск, HUD и движение; '
             'реальная игра двух пользователей и комбинации VPN ещё требуют проверки. '
             'Эти инструкции проверяют Minecraft-сеть SkyCraft; совместные квесты Skyrim Together и конвертация предметов пока не реализованы.')
    sections = [('# Тестирование SkyCraft: подробная инструкция',intro)]
    groups = [
        ('Подготовка обоих ПК',PREPARATION,['Код','Действие','Что сделать','Что проверить']),
        ('Как получить адрес хоста',ADDRESSES,['Код','Ветка','Точные действия','Результат / ограничения']),
        ('Как задать сетевой режим',TRANSPORTS,['Код','Режим','Точные действия','Проверка']),
        ('Общий прогон по шагам',STEPS,['Шаг','Этап','Хост','Клиент','Что фиксировать']),
        ('Откуда брать данные',DATA,['Данные','Источник','Куда записать']),
        ('Разбор неудачного шага',FAILURES,['Симптом','Что проверить','Как трактовать']),
    ]
    md = [sections[0][0], '', intro, '', 'Все подписи HOST_LAN_IP, HOST_WAN_IP, ENDPOINT и RUN_ID заменяются вашими значениями. Их нельзя вставлять буквально. Команды /... вводятся в чате Minecraft через T в Skyrim; команды Windows — в PowerShell. Примеры IP не являются адресами ваших ПК.', '', 'Таблица: [SkyCraft-Network-Tests.xlsx](testing/SkyCraft-Network-Tests.xlsx). В «Сценарии подробно» отфильтруйте ID: там полный порядок конкретного сценария, включая команды с его Run ID.', '']
    html_sections = []
    for index,(title,rows,headers) in enumerate(groups):
        md += ['## '+title,'']
        blocks=[]
        for row in rows:
            if len(row)==len(headers) and len(row)>=4:
                md += ['### '+str(row[0])+' — '+str(row[1]),'']
                body=[]
                for label,value in zip(headers[2:],row[2:]):
                    md += ['**'+label+':** '+str(value),'']
                    body.append('<p><strong>'+html.escape(label)+':</strong> '+html.escape(str(value))+'</p>')
                blocks.append('<article id="'+html.escape(str(row[0]))+'"><h3>'+html.escape(str(row[0])+' — '+str(row[1]))+'</h3>'+''.join(body)+'</article>')
            else:
                md += ['- **'+str(row[0])+':** '+' '.join(str(x) for x in row[1:]),'']
                blocks.append('<article><h3>'+html.escape(str(row[0]))+'</h3>'+''.join('<p>'+html.escape(str(x))+'</p>' for x in row[1:])+'</article>')
        html_sections.append('<section id="section'+str(index)+'"><h2>'+html.escape(title)+'</h2>'+''.join(blocks)+'</section>')
    md += ['## Особенности каждого сценария','', 'Для положительных сценариев выполните условия ниже, затем шаги 1–12. Полный повтор шагов для каждого ID есть во вкладке «Сценарии подробно». Для отрицательных S02/S03/E01/E02 действуют их собственные шаги и критерии отказа.', '']
    scenario_html=[]
    for s in scenarios:
        sid=s['id']; title=sid+' — '+s['name']; md += ['### '+title,'']
        content=[]
        if sid in NEGATIVE:
            for step,host,client,expected in NEGATIVE[sid]:
                md += ['**'+step+' / хост или один ПК:** '+host,'','**Клиент:** '+client,'','**Ожидание:** '+expected,'']
                content += [host,client,expected]
        else:
            condition,action=SCENARIO_SETUP[sid];md += [condition,'',action,''];content += [condition,action]
        md += ['**Минимум:** '+str(s['minutes'])+' минут. **PASS:** '+s['expected'],'','**Ограничение:** '+s['limit'],'']
        scenario_html.append('<details id="'+sid+'"><summary>'+html.escape(title)+'</summary>'+''.join('<p>'+html.escape(x)+'</p>' for x in content)+f'<p>Минимум {s["minutes"]} минут.</p><p><strong>PASS:</strong> '+html.escape(s['expected'])+'</p><p>'+html.escape(s['limit'])+'</p></details>')
    html_sections.append('<section id="scenarios"><h2>Особенности каждого сценария</h2>'+''.join(scenario_html)+'</section>')
    md += ['## Итоги и отправка','', 'PASS — все ожидания данного сценария выполнены. FAIL — ожидание нарушено. BLOCKED — нет необходимых условий (например публичного входящего адреса). PARTIAL — проверена только часть, не выдержана длительность или не подтверждена нужная сетевая среда. NOT_RUN — не запускали.', '', 'Передайте заполненную XLSX и два ZIP с одним ID (host/client). При проблеме установки дополнительно installation-*.zip. Для запуска без debug start: «Собрать отчёт.cmd», режим 1, последний AUTO JSONL будет собран автоматически. Полные игровые логи включаются только по выбору yes; они могут содержать чат и имена. Конфиг с паролями и accounts.json не отправляйте.', '', '## Документация команд','']
    for label,url in SOURCES: md += ['- ['+label+']('+url+')']
    (ROOT/'docs/YS-TESTING.md').write_text('\n'.join(md)+'\n',encoding='utf-8')
    nav=''.join(f'<a href="#section{i}">{html.escape(title)}</a>' for i,(title,_,_) in enumerate(groups))+'<a href="#scenarios">25 сценариев</a>'
    document='<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>SkyCraft — подробные тесты</title><style>body{font:17px/1.65 system-ui,sans-serif;color:#213547;background:#f5f8fc;margin:0}header,main{max-width:1100px;margin:auto;padding:24px}header{background:#17324d;color:white;border-radius:0 0 20px 20px}nav{display:flex;gap:12px;flex-wrap:wrap}nav a{color:#d0f3fa}article,details{background:white;padding:18px 24px;border:1px solid #dce5ed;border-radius:12px;margin:16px 0}h2{margin-top:44px}h3{margin:0}summary{cursor:pointer;font-weight:700}article{scroll-margin-top:12px}p{white-space:pre-wrap;overflow-wrap:anywhere}</style><header><h1>SkyCraft — подробные сетевые тесты</h1><p>'+html.escape(intro)+'</p><p>HOST_LAN_IP, HOST_WAN_IP, ENDPOINT, RUN_ID заменяются вашими данными. Команды /... — чат T в Skyrim; Windows-команды — PowerShell.</p><nav>'+nav+'</nav></header><main>'+''.join(html_sections)+'<section><h2>Результаты и источники</h2><p>PASS = выполнено ожидание. FAIL = нарушено. BLOCKED = нет условий. PARTIAL = часть проверки. NOT_RUN = не запускали. Отправьте XLSX и два ZIP с одним Run ID; режим 1 сборщика предназначен для проблемы запуска без ручного лога.</p>'+''.join('<p><a href="'+html.escape(url)+'">'+html.escape(label)+'</a></p>' for label,url in SOURCES)+'</section></main></html>'
    (OUT/'ИНСТРУКЦИЯ.html').write_text(document,encoding='utf-8')

def main():
    OUT.mkdir(parents=True,exist_ok=True)
    scenarios=json.loads((OUT/'scenarios.json').read_text(encoding='utf-8'))
    assert set(s['id'] for s in scenarios)==set(SCENARIO_SETUP)|set(NEGATIVE), 'Missing scenario instructions'
    book=Workbook();book.remove(book.active)
    start_rows=[
        ['1','Существующий SkyCraft работает','Распаковать friend-kit вне игры → Установить обновление.cmd → Проверить сборку.cmd → одиночный HUD/движение.'],
        ['2','Первый тест с другом','В одной LAN — L01. В разных сетях — I01: WAN IP/проброс TCP или доступный IPv6. Адрес получить по вкладке Адрес хоста, не наугад.'],
        ['3','Читать конкретный сценарий','В Сценарии подробно включить фильтр столбца ID, оставить нужный ID. Там полный порядок с командами и нужным Run ID. Подготовка и Адрес хоста объясняют источники переменных.'],
        ['4','Ввод команд','T в Skyrim открывает чат Minecraft; /... вводить там. Команды Get-/Test- вводить в Windows PowerShell. Все подписи ENDPOINT/IP заменить своим значением.'],
        ['5','Логи и результат','Прогоны — наблюдения/метрики, Условия прогонов — адрес/версии/режимы. В конце два ZIP с одним ID. PASS TCP не означает PASS игрового сценария.'],
        ['6','Запуск без сети','Проблема HUD/движения: Собрать отчёт.cmd режим 1. Он соберёт AUTO JSONL; ручной Run ID ещё не нужен.'],
    ]
    start=page(book,'Начать',['№','Ситуация','Порядок'],start_rows,[7,36,105],'Start')
    page(book,'Подготовка',['Код','Действие','Что сделать','Ожидание'],PREPARATION,[8,30,90,65],'Preparation')
    page(book,'Адрес хоста',['Код','Ветка','Точные действия','Что получить / ограничения'],ADDRESSES,[8,32,96,70],'Addresses')
    page(book,'Настройка сети',['Код','Режим','Точные действия','Что проверить'],TRANSPORTS,[8,30,90,68],'Transports')
    page(book,'Шаги',['Шаг','Этап','Хост','Клиент','Что фиксировать'],STEPS,[8,28,70,74,64],'Steps')
    matrix_rows=[[s[k] for k in ('id','name','transport','minutes','conditions','expected')] for s in scenarios]
    matrix=page(book,'Матрица',['ID','Сценарий','Транспорт','Минуты','Условия','PASS'],matrix_rows,[9,38,24,10,68,75],'Scenarios')
    detailed=scenario_rows(scenarios)
    page(book,'Сценарии подробно',['ID','Шаг','Этап','Хост / один ПК','Клиент','Ожидание / запись'],detailed,[8,10,28,75,75,70],'Detailed')
    run_headers=['Сценарий','Run ID','Результат','TCP','Вход, мс','Ping сред., мс','Ping макс., мс','Минуты факт','Обрывы','Движение обоим','Блоки обоим','Leave / rejoin','ZIP хоста','ZIP клиента','Шаг / UTC / сообщение / заметки']
    condition_headers=['Run ID','Начало UTC','Сеть','Адрес:порт','Источник адреса / внутренний и внешний порт','Версии хоста','Режимы хоста / версии / профиль / порядок','Версии клиента','Режимы клиента / версии / профиль / порядок','Транспорт клиента','Прокси клиента:порт (без пароля)']
    run_rows=[];condition_rows=[]
    for s in scenarios:
        for repeat in range(1,4):
            run_id=f'{s["id"]}_{repeat:02}';run_rows.append([s['id'],run_id,'NOT_RUN']+[None]*(len(run_headers)-3));condition_rows.append([run_id]+[None]*(len(condition_headers)-1))
    runs=page(book,'Прогоны',run_headers,run_rows,[10,16,17,17,14,16,16,14,11,18,17,22,32,32,65],'Runs')
    page(book,'Условия прогонов',condition_headers,condition_rows,[16,24,16,32,56,40,62,40,62,23,34],'RunConditions')
    choices={'C':'PASS,FAIL,BLOCKED,PARTIAL,NOT_RUN','D':'OK,REFUSED,TIMEOUT,DNS_ERROR,AUTH_ERROR,NOT_RUN','J':'YES,NO,NOT_RUN','K':'YES,NO,NOT_RUN','L':'OK,FAIL,NOT_RUN'}
    for col,items in choices.items():
        validation=DataValidation(type='list',formula1='"'+items+'"',allow_blank=True);validation.showErrorMessage=True;runs.add_data_validation(validation);validation.add(f'{col}2:{col}1001')
    for col in 'EFGHI':
        validation=DataValidation(type='decimal',operator='greaterThanOrEqual',formula1=0,allow_blank=True);validation.showErrorMessage=True;runs.add_data_validation(validation);validation.add(f'{col}2:{col}1001')
    for result,color in [('PASS','DDF3E4'),('FAIL','FCE4E4'),('BLOCKED','FFF0C2'),('PARTIAL','DFECFC')]:
        runs.conditional_formatting.add('C2:C76',FormulaRule(formula=[f'C2="{result}"'],fill=PatternFill('solid',fgColor=color)))
    page(book,'Откуда данные',['Поле','Источник','Куда записать'],DATA,[28,92,86],'Sources')
    page(book,'Разбор сбоя',['Симптом','Точные проверки','Трактовка'],FAILURES,[30,94,84],'Failures')
    for row,s in enumerate(scenarios,2):
        target=next(i+2 for i,r in enumerate(detailed) if r[0]==s['id']);matrix.cell(row,1).hyperlink=f"#'Сценарии подробно'!A{target}";matrix.cell(row,1).font=Font(color='087F8C',underline='single')
    for row,name in enumerate(book.sheetnames[1:],start.max_row+3):
        start.cell(row,2,'Открыть: '+name).hyperlink=f"#'{name}'!A1";start.cell(row,2).font=Font(color='087F8C',underline='single')
    path=OUT/'SkyCraft-Network-Tests.xlsx';book.save(path)
    original_headers=['ID','Когда','Сценарий','Транспорт','Что делает хост','Что делает клиент','Условия','Ожидаемый результат','Минуты','Ограничение']
    csv_file('scenarios.csv',original_headers,[[s[k] for k in ('id','stage','name','transport','host','client','conditions','expected','minutes','limit')] for s in scenarios])
    csv_file('runs-template.csv',run_headers,run_rows);csv_file('conditions-template.csv',condition_headers,condition_rows)
    csv_file('detailed-steps.csv',['ID','Шаг','Этап','Хост / один ПК','Клиент','Ожидание'],detailed)
    markdown_and_html(scenarios)
    check=load_workbook(path)
    assert len(check.worksheets)==11 and check['Матрица'].max_row==26 and check['Прогоны'].max_row==76
    assert all(check['Прогоны'].cell(row,3).value=='NOT_RUN' for row in range(2,77))
    assert len(set(r[0] for r in detailed))==25 and all(len(r)==6 for r in detailed)
    assert 'Передать доступный клиенту адрес' not in (ROOT/'docs/YS-TESTING.md').read_text(encoding='utf-8')
    print(f'DETAILED TEST KIT PASS: 25 scenarios, {len(detailed)} detailed rows, 75 empty runs, 11 tabs, standalone HTML and Markdown')

if __name__=='__main__':main()
