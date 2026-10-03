"""Readable, fully self-contained Russian guides generated from the workbook data."""
import html
from pathlib import Path
import re

from network_test_guide import BASICS, REPORTS, SOLO, PREPARATION, ADDRESSES, TRANSPORTS, STEPS, DATA, FAILURES, SOURCES

ROOT = Path(__file__).resolve().parents[1]
INTRO = ('Сборка 0.1.2-ys.network.2: Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.161.0+26.3, Java 25. '
         'Проверенный Skyrim runtime 1.7.104.0, SKSE 2.3.1. На текущем ПК проверены запуск, HUD и движение. '
         'Совместные квесты Skyrim Together и конвертация предметов пока не реализованы.')
CSS = '''body{font:17px/1.65 system-ui,sans-serif;color:#213547;background:#f5f8fc;margin:0}
header,main{max-width:1100px;margin:auto;padding:24px}header{background:#17324d;color:white;border-radius:0 0 20px 20px}
nav{display:flex;gap:12px;flex-wrap:wrap}nav a{color:#d0f3fa}article,details{background:white;padding:18px 24px;border:1px solid #dce5ed;border-radius:12px;margin:16px 0}
h2{margin-top:44px}h3{margin:0}summary{cursor:pointer;font-weight:700}article,section,details{scroll-margin-top:12px}
p{white-space:pre-wrap;overflow-wrap:anywhere}code{font:15px/1.7 Consolas,monospace;background:#eaf1f7;padding:3px 6px;border-radius:4px}
details article{padding:14px;border:0;border-top:1px solid #dce5ed;border-radius:0}a{color:#087f8c}'''


def is_command(line):
    return bool(re.match(r'^(?:/(?:skycraft|join|leave)\b|(?:join|network\.[\w.]+)=|Get-Net|Test-Net|\[DateTime\]|powershell\b)', line))


def html_text(text):
    result = []
    for line in str(text).splitlines():
        if is_command(line):
            result.append('<code>'+html.escape(line)+'</code>')
        else:
            result.append(re.sub(r'\b(G[1-8]|R[1-6]|B(?:1[01]|[0-9]))\b',
                lambda match: '<a href="#'+match.group(0)+'">'+match.group(0)+'</a>', html.escape(line)))
    return '\n'.join(result)


def md_text(text):
    return '\n\n'.join('`'+line+'`' if is_command(line) else line for line in str(text).splitlines())


def row_content(row, headers, anchor):
    title = str(row[0]) + ' — ' + str(row[1]) if len(row) >= 4 else str(row[0])
    values = list(zip(headers[2:], row[2:])) if len(row) >= 4 else list(zip(headers[1:], row[1:]))
    md = ['### '+title, '']
    blocks = []
    for label, value in values:
        md += ['**'+label+':**', '', md_text(value), '']
        blocks.append('<p><strong>'+html.escape(label)+':</strong>\n'+html_text(value)+'</p>')
    return md, '<article id="'+html.escape(anchor)+'"><h3>'+html.escape(title)+'</h3>'+''.join(blocks)+'</article>'


def document(title, intro, nav, body):
    return '<!doctype html>\n<html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>'+html.escape(title)+'</title><style>'+CSS+'</style><header><h1>'+html.escape(title)+'</h1><p>'+html.escape(intro)+'</p><nav>'+nav+'</nav></header><main>'+''.join(body)+'</main></html>\n'


def write_guide(title, intro, groups, md_path, html_path, scenarios=None, detailed=None):
    md = ['# '+title, '', intro, '',
          'Начните с G1–G8: там найден путь файла, способ сохранения и объяснение команд. Все CMD запускаются из распакованного комплекта. MINECRAFT обозначает полный путь из Prism; он не вставляется в команду буквально.', '',
          '[Таблица Excel](testing/SkyCraft-Network-Tests.xlsx). [Проверка без друга](YS-SOLO.md).', '']
    body = []
    nav = []
    for index, (group_title, rows, headers) in enumerate(groups):
        anchor = 'section'+str(index)
        nav.append('<a href="#'+anchor+'">'+html.escape(group_title)+'</a>')
        md += ['## '+group_title, '']
        articles = []
        for row in rows:
            row_md, row_html = row_content(row, headers, str(row[0]))
            md += row_md
            articles.append(row_html)
        body.append('<section id="'+anchor+'"><h2>'+html.escape(group_title)+'</h2>'+''.join(articles)+'</section>')
    if scenarios:
        nav.append('<a href="#scenarios">Все 25 сценариев</a>')
        md += ['## Полный порядок каждого сценария', '',
               'Выполните подготовку и получение адреса до выбранного сценария. Положительные сценарии содержат полный порядок 1–12 и дополнительные действия, если они нужны. S01–S04/E01/E02 имеют собственные шаги. Не выполняйте сразу все сценарии: сначала базовый L01 или I01, затем нужную комбинацию.', '']
        cards = []
        for scenario in scenarios:
            sid = scenario['id']
            scenario_title = sid+' — '+scenario['name']
            md += ['### '+scenario_title, '', '**Минимальная длительность:** '+str(scenario['minutes'])+' минут; для S03 — отдельно на каждый режим.', '',
                   '**Ожидаемый результат:** '+scenario['expected'], '', '**Ограничения:** '+scenario['limit'], '']
            steps = []
            for row in (r for r in detailed if r[0] == sid):
                _, number, step_title, host, client, expected = row
                step_md, step_html = row_content([number, step_title, host, client, expected],
                    ['Шаг', 'Этап', 'Хост / один ПК', 'Клиент', 'Ожидание и запись'], sid+'-'+number)
                md += step_md
                steps.append(step_html)
            cards.append('<details id="'+sid+'"><summary>'+html.escape(scenario_title)+'</summary><p>Минимум '+str(scenario['minutes'])+' минут. '+html.escape(scenario['expected'])+'</p><p>'+html.escape(scenario['limit'])+'</p>'+''.join(steps)+'</details>')
        body.append('<section id="scenarios"><h2>Полный порядок каждого сценария</h2><p>Нажмите название нужного сценария: внутри все его шаги. Для новой попытки замените _01 на новый номер во всех командах и отчёте. В S03_01 проверяется SOCKS5, в S03_02 — HTTP CONNECT.</p>'+''.join(cards)+'</section>')
    md += ['## Как оценить результат', '',
           'PASS — все ожидания именно этого сценария выполнены. FAIL — ожидание нарушено. BLOCKED — нет необходимых условий. PARTIAL — проверена только часть или условие среды не подтверждено. NOT_RUN — не запускали. Для S02/S03 ожидаемая ошибка может дать PASS.', '',
           'Для одного ПК передайте отчёты B10; для двух ПК — ZIP хоста и клиента с одним ID и рабочую XLSX. Конфиг, аккаунты и резервные копии мира не отправляйте. Сборщик ничего сам не отправляет.', '', '## Источники', '']
    md += ['- ['+label+']('+url+')' for label, url in SOURCES]
    body.append('<section><h2>Результаты и источники</h2><p>PASS — выполнено ожидание данного сценария. FAIL — нарушено. BLOCKED — нет условий. PARTIAL — проверена часть. NOT_RUN — не запускали. Для одного ПК используйте B10; для двух — два ZIP с общим ID и рабочую XLSX.</p>'+''.join('<p><a href="'+html.escape(url)+'">'+html.escape(label)+'</a></p>' for label, url in SOURCES)+'</section>')
    md_path.write_text('\n'.join(md)+'\n', encoding='utf-8')
    html_path.write_text(document(title, intro, ''.join(nav), body), encoding='utf-8')


def render_guides(scenarios, detailed):
    four = ['Код', 'Действие', 'Что сделать', 'Что проверить']
    groups = [
        ('Пути, файлы и ввод команд', BASICS, four),
        ('Подготовка участников', PREPARATION, four),
        ('Как получить адрес хоста', ADDRESSES, four),
        ('Как изменить сетевой режим', TRANSPORTS, four),
        ('Проверка без друга: один компьютер', SOLO, four),
        ('Общий прогон с другом', STEPS, ['Шаг', 'Этап', 'Хост', 'Клиент', 'Что фиксировать']),
        ('Сбор отчётов: каждое поле', REPORTS, four),
        ('Откуда брать данные', DATA, ['Поле', 'Источник', 'Куда записать']),
        ('Если шаг не удался', FAILURES, ['Симптом', 'Что проверить', 'Как трактовать']),
    ]
    write_guide('Тестирование SkyCraft: пошаговая инструкция', INTRO, groups,
        ROOT/'docs/YS-TESTING.md', ROOT/'docs/testing/ИНСТРУКЦИЯ.html', scenarios, detailed)
    solo_intro = ('Готовая последовательность для одного ПК: S01_01 → S02_01 → S03_01 (SOCKS5) → S03_02 (HTTP CONNECT) → S01_02 (возврат DIRECT). '
                  'Сначала прочитайте G1–G8, затем выполняйте B0–B10 по порядку. B11 — необязательный тест разработчика. '
                  'Второй игрок и сторонний сетевой аккаунт для этой последовательности не нужны.')
    write_guide('SkyCraft: проверка без друга', solo_intro,
        [('Пути, файлы и ввод команд', BASICS, four), ('Проверка одному по шагам', SOLO, four), ('Сбор отчётов: каждое поле', REPORTS, four)],
        ROOT/'docs/YS-SOLO.md', ROOT/'docs/testing/БЕЗ-ДРУГА.html')
