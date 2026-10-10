"""Package verified upstream resources, optional built native DLL, YS Java mod and test tools.

Uses the pristine upstream release, never an installed Prism/profile/game folder.
Download SkyCraft-0.1.2.zip from its official release to the cache first.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE_REVISION = 7
UPSTREAM_SHA256 = '1133ecde384d70d5261cbc9ba149bc7b544e1653b78846a1623b56241bb392c3'
NATIVE_SHA256 = '72b0d231f4632cf2268514eece90e9b7adaa2b59c648fcf14aaf55a6513eefa3'
API_SHA512 = 'ed6b2586d6fde11fde8472f5a527c51e99b67026e46f94d4bfd85e7e28ce5ee299173ee16ad576ceb51f39f98d30a811086a6deb1a86a524859cc16e12da109d'
MODS_PREFIX = 'Prism/instances/SkyCraft/.minecraft/mods/'

def sha(data): return hashlib.sha256(data).hexdigest()

def zip_bytes(entries):
    result = io.BytesIO()
    with zipfile.ZipFile(result, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for name,data in sorted(entries.items()):
            path=PurePosixPath(name)
            if path.is_absolute() or '..' in path.parts or '\\' in name: raise ValueError('Unsafe ZIP entry '+name)
            entry=zipfile.ZipInfo(name, date_time=(2026,10,3,0,0,0))
            entry.compress_type=zipfile.ZIP_DEFLATED
            archive.writestr(entry,data)
    return result.getvalue()

def mod_metadata(data):
    with zipfile.ZipFile(io.BytesIO(data)) as z: return json.loads(z.read('fabric.mod.json'))

def build(output, native_dll=None):
    properties=(ROOT/'fabric/gradle.properties').read_text()
    version=re.search(r'^version=(.+)$',properties,re.M).group(1).strip()
    if not re.fullmatch(r'[A-Za-z0-9._-]+',version): raise ValueError('Invalid version')
    if version=='0.1.2-ys.network.6' and native_dll is None:raise ValueError('network.6 requires the built native DLL; pass --native-dll')
    upstream=ROOT/'.tools/friend-package/cache/SkyCraft-0.1.2.zip'
    data=upstream.read_bytes()
    if sha(data)!=UPSTREAM_SHA256: raise ValueError('Official upstream ZIP checksum mismatch')
    jar=(ROOT/f'fabric/build/libs/skycraft-{version}.jar').read_bytes()
    if mod_metadata(jar)['id']!='skycraft' or mod_metadata(jar)['version']!=version: raise ValueError('Wrong Fabric JAR')
    if b'e4mc' in json.dumps(mod_metadata(jar).get('depends',{})).encode(): raise ValueError('e4mc still required')
    jar_name=f'skycraft-fabric-{version}.jar'
    with zipfile.ZipFile(io.BytesIO(data)) as release:
        dll=release.read('SKSE/Plugins/SkyCraft.dll')
        if sha(dll)!=NATIVE_SHA256: raise ValueError('Native DLL mismatch')
        with zipfile.ZipFile(io.BytesIO(release.read('SKSE/Plugins/SkyCraft/SkyCraft-Minecraft.zip'))) as bundle:
            bundle_entries={}
            for name in bundle.namelist():
                if name.endswith('/'):continue
                if any(p.lower() in ('accounts.json','saves','logs','config') for p in PurePosixPath(name).parts): raise ValueError('User data found in upstream bundle')
                if name.startswith(MODS_PREFIX) and Path(name).name.startswith(('skycraft-','e4mc-')):continue
                bundle_entries[name]=bundle.read(name)
    if native_dll is not None:
        dll=native_dll.read_bytes()
        if len(dll)<1048576 or dll[:2]!=b'MZ':raise ValueError('Invalid built native DLL')
    api_name='fabric-api-0.161.0+26.3.jar'
    api=bundle_entries[MODS_PREFIX+api_name]
    if hashlib.sha512(api).hexdigest()!=API_SHA512: raise ValueError('Fabric API checksum mismatch')
    for name in ('Prism/instances/SkyCraft/instance.cfg','Prism/instances/SkyCraft/mmc-pack.json','defaults/prismlauncher.cfg'):
        bundle_entries[name]=(ROOT/'tools/minecraft-bundle'/name).read_bytes()
    bundle_entries[MODS_PREFIX+jar_name]=jar
    bundle_entries['bundle-version.txt']=f'SkyCraft YS {version}, native {version if native_dll else "upstream 0.1.2"}, Prism 11.1.1, {api_name}; no e4mc'.encode()
    native_entries={
        'SKSE/Plugins/SkyCraft.dll':dll,
        'SKSE/Plugins/SkyCraft.ini':(ROOT/'skse/SkyCraft.ini').read_bytes(),
        'SKSE/Plugins/SkyCraft/SkyCraft-Minecraft.zip':zip_bytes(bundle_entries),
        'SKSE/Plugins/SkyCraft/LICENSE.txt':(ROOT/'LICENSE').read_bytes(),
        'SKSE/Plugins/SkyCraft/THIRD-PARTY-NOTICES.md':(ROOT/'THIRD-PARTY-NOTICES.md').read_bytes(),
    }
    mod_name=f'SkyCraft-YS-{version}-MO2.zip';mod_zip=zip_bytes(native_entries)
    instance_name=f'SkyCraft-YS-{version}-Prism-instance.zip'
    instance_zip=zip_bytes({
        'SkyCraft-YS/instance.cfg':(ROOT/'tools/minecraft-bundle/Prism/instances/SkyCraft/instance.cfg').read_bytes().replace(b'name=SkyCraft',b'name=SkyCraft YS'),
        'SkyCraft-YS/mmc-pack.json':(ROOT/'tools/minecraft-bundle/Prism/instances/SkyCraft/mmc-pack.json').read_bytes(),
        'SkyCraft-YS/.minecraft/mods/'+jar_name:jar,
        'SkyCraft-YS/.minecraft/mods/'+api_name:api,
    })
    manifest={
        'schema':1,'version':version,'packageRevision':PACKAGE_REVISION,
        'sourceCommit':subprocess.check_output(['git','-c',f'safe.directory={ROOT}','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
        'upstream':{'url':'https://github.com/chasmlol/SkyCraft/releases/tag/v0.1.2','zipSha256':UPSTREAM_SHA256},
        'fabricJar':{'file':jar_name,'sha256':sha(jar)},
        'nativeDll':{'version':f'{version}-r{PACKAGE_REVISION}' if native_dll else '0.1.2','file':'native/SkyCraft.dll','sha256':sha(dll),'changed':native_dll is not None,'upstreamSha256':NATIVE_SHA256},
        'mo2Archive':{'file':mod_name,'sha256':sha(mod_zip)},
        'prismInstance':{'file':instance_name,'sha256':sha(instance_zip)},
        'requirements':{'skyrimRuntime':'1.7.104.0','skse':'2.3.1','skseRuntimeDll':'skse64_1_7_104.dll','addressLibrary':'versionlib-1-7-104-0.bin','minecraft':'26.3','fabricLoader':'0.19.5','fabricApi':'0.161.0+26.3','java':25},
        'verification':'Native r7 built and installed; user confirmed two previously failing saves loaded with Minecraft HUD and movement. Java network.6 is unchanged from the tested LAN/PLAY/dig/shield/armor build. Native doors/knockdown and gameplay between PCs still require acceptance tests.' if native_dll else 'Upstream native DLL verified by hash; gameplay acceptance remains required.'
    }
    entries={jar_name:jar,mod_name:mod_zip,instance_name:instance_zip,'package-manifest.json':(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n').encode(),'LICENSE.txt':(ROOT/'LICENSE').read_bytes(),'THIRD-PARTY-NOTICES.md':(ROOT/'THIRD-PARTY-NOTICES.md').read_bytes()}
    if native_dll:entries['native/SkyCraft.dll']=dll
    docs=['YS-FRIEND.md','YS-TESTING.md','YS-SOLO.md','YS-AUTOTEST.md','YS-NETWORK.md','YS-STARTUP.md','testing/SkyCraft-Network-Tests.xlsx','testing/scenarios.csv','testing/runs-template.csv','testing/conditions-template.csv','testing/detailed-steps.csv','testing/solo-steps.csv','testing/ИНСТРУКЦИЯ.html','testing/БЕЗ-ДРУГА.html']
    docs.extend(['YS-RETEST.md','YS-NETWORK-ENVIRONMENT.md','YS-NETWORK4-VALIDATION.md','testing/retest-steps.csv','testing/ПОВТОР.html'])
    for name in docs:entries['docs/'+name]=(ROOT/'docs'/name).read_bytes()
    entries['ТЕСТЫ-БЕЗ-ДРУГА.html']=(ROOT/'docs/testing/БЕЗ-ДРУГА.html').read_bytes()
    scripts=['skycraft-package-common.ps1','install-friend-update.ps1','check-friend-installation.ps1','collect-friend-report.ps1','collect-network-report.ps1','test-report-wizard.ps1','show-host-addresses.ps1','capture-skyrim-startup.ps1','assistant-worker.ps1','collect-test-environment.ps1']
    for name in scripts:
        text=(ROOT/'tools'/name).read_text(encoding='utf-8-sig')
        entries['tools/'+name]=text.encode('utf-8-sig')
    for name in ('collect-network-report.cmd','capture-skyrim-startup.cmd'):
        entries['tools/'+name]=(ROOT/'tools'/name).read_bytes()
    assistant=(ROOT/'.tools/assistant-build/SkyCraft-Test-Assistant.exe').read_bytes()
    if assistant[:2]!=b'MZ':raise ValueError('Build the Windows assistant before packaging')
    for name in ('SkyCraft-Хост.exe','SkyCraft-Клиент.exe','SkyCraft-Без-друга.exe'):
        entries[name]=assistant
    entries['АВТОТЕСТЫ.html']=autotest_html()
    entries['ИГРОВЫЕ-ПРОВЕРКИ.html']=guide_html('YS-GAMEPLAY.md')
    entries['ИСПРАВЛЕНИЕ-ЗАГРУЗКИ.html']=guide_html('YS-LOADFIX-R7.md')
    entries['docs/YS-LOADFIX-R7.md']=(ROOT/'docs/YS-LOADFIX-R7.md').read_bytes()
    entries['docs/YS-GAMEPLAY.md']=(ROOT/'docs/YS-GAMEPLAY.md').read_bytes()
    entries['docs/YS-NETWORK6-VALIDATION.md']=(ROOT/'docs/YS-NETWORK6-VALIDATION.md').read_bytes()
    wrappers={'Установить обновление.cmd':'install-friend-update.ps1','Проверить сборку.cmd':'check-friend-installation.ps1','Собрать отчёт.cmd':'collect-friend-report.ps1','Адреса хоста.cmd':'show-host-addresses.ps1','Наблюдать запуск.cmd':'capture-skyrim-startup.ps1'}
    for label,script in wrappers.items():
        entries[label]=('@echo off\r\npowershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\\'+script+'" %*\r\nif errorlevel 1 echo Failed. Read the message above.\r\npause\r\n').encode('ascii')
    entries['НАЧАТЬ.txt']=(f'SkyCraft {version}, комплект r{PACKAGE_REVISION} — обновление ОБЕИХ частей\r\n'
        'DLL r6 отозвана: вылет при загрузке на AE. r7 исправляет этот вызов. Сначала прочитайте ИСПРАВЛЕНИЕ-ЗАГРУЗКИ.html.\r\n'
        '1. Распакуйте весь ZIP вне папки Skyrim.\r\n2. Откройте ИГРОВЫЕ-ПРОВЕРКИ.html: это актуальная инструкция к этой версии.\r\n'
        '3. Закройте Skyrim/Minecraft. Запустите помощник и нажмите «Обновить мод».\r\n'
        '4. При запросе выберите установленную SkyCraft.dll: MO2 -> правой кнопкой по SkyCraft -> Открыть в проводнике -> SKSE -> Plugins.\r\n'
        '5. Запустите SKSE/MO2, загрузите тестовое сохранение. В помощнике нажмите «Игровой тест до остановки».\r\n'
        '6. Без друга выберите роль «Без друга». С другом хост передаёт новый код, клиент вставляет его.\r\n'
        '7. Следуйте ИГРОВЫЕ-ПРОВЕРКИ.html, отметьте результаты и нажмите «Остановить и собрать отчёт».\r\n'
        'ZIP отчёта включает игровые логи и свежий дамп Skyrim при наличии; остаётся на ПК. Не публикуйте его на GitHub.\r\n'
        'Оба игрока должны обновить JAR и DLL. Двери/вылет требуют проверки в настоящем Skyrim. STR и физическая экипировка Skyrim ещё не реализованы.\r\n').encode('utf-8-sig')
    hashes=''.join(sha(value)+'  '+name+'\n' for name,value in sorted(entries.items()))
    entries['SHA256.txt']=hashes.encode('utf-8')
    archive=output/f'SkyCraft-YS-friend-kit-{version}-r{PACKAGE_REVISION}.zip'
    output.mkdir(parents=True,exist_ok=True)
    if archive.exists():raise FileExistsError('Preserve the existing kit or choose --output-directory')
    archive.write_bytes(zip_bytes(entries))
    archive.with_suffix('.zip.sha256').write_text(sha(archive.read_bytes())+'  '+archive.name+'\n',encoding='ascii')
    print(json.dumps({'archive':str(archive),'bytes':archive.stat().st_size,'sha256':sha(archive.read_bytes()),'files':len(entries)},ensure_ascii=True))
    return archive

def autotest_html():
    return guide_html('YS-RETEST.md')

def guide_html(document):
    import html
    import re
    source=(ROOT/'docs'/document).read_text(encoding='utf-8')
    # Standalone UTF-8 guide, readable offline without a Markdown editor.
    lines=[]
    for line in source.splitlines():
        escaped=html.escape(line)
        if line.startswith('# '):lines.append('<h1>'+escaped[2:]+'</h1>')
        elif line.startswith('## '):lines.append('<h2>'+escaped[3:]+'</h2>')
        else:
            escaped=re.sub(r'\*\*(.+?)\*\*',r'<strong>\1</strong>',escaped)
            escaped=re.sub(r'`(.+?)`',r'<code>\1</code>',escaped)
            escaped=re.sub(r'\[([^\]]+)\]\((https://[^\s)]+)\)',r'<a href="\2">\1</a>',escaped)
            lines.append('<div>'+escaped+'</div>' if line else '<br>')
    return ('<!doctype html><html lang="ru"><meta charset="utf-8"><title>Автотесты SkyCraft</title><style>body{max-width:1000px;margin:40px auto;padding:0 24px;font:18px/1.55 system-ui;color:#213547}h1,h2{color:#17324d}code{background:#edf2f7;padding:2px 5px}</style>'+''.join(lines)+'</html>').encode('utf-8')

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-directory',type=Path,default=ROOT/'dist')
    parser.add_argument('--native-dll',type=Path,help='Built native DLL, never an installed game file')
    args=parser.parse_args();build(args.output_directory,args.native_dll)
