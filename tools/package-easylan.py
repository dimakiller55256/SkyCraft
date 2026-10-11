"""Package standalone EasyLAN, verified Fabric API, sources, installer and offline guide."""
from pathlib import Path, PurePosixPath
import zipfile, io, json, hashlib, html, re
ROOT=Path(__file__).resolve().parents[1]
VERSION='1.6a-ys.26.3.1'
def sha(data):return hashlib.sha256(data).hexdigest()
def zipped(entries):
    out=io.BytesIO()
    with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
        for name,data in sorted(entries.items()):
            assert not PurePosixPath(name).is_absolute() and '..' not in PurePosixPath(name).parts and '\\' not in name
            entry=zipfile.ZipInfo(name,(2026,10,5,0,0,0));entry.compress_type=zipfile.ZIP_DEFLATED;z.writestr(entry,data)
    return out.getvalue()
def metadata(data):
    with zipfile.ZipFile(io.BytesIO(data)) as z:return json.loads(z.read('fabric.mod.json'))
def guide(text):
    rows=[]
    for line in text.splitlines():
        line=html.escape(line)
        line=re.sub(r'\*\*(.+?)\*\*',r'<strong>\1</strong>',line)
        line=re.sub(r'`(.+?)`',r'<code>\1</code>',line)
        line=re.sub(r'\[([^\]]+)\]\((https://[^\s)]+)\)',r'<a href="\2">\1</a>',line)
        if line.startswith('## '):rows.append('<h2>'+line[3:]+'</h2>')
        elif line.startswith('# '):rows.append('<h1>'+line[2:]+'</h1>')
        else:rows.append('<div>'+line+'</div>' if line else '<br>')
    return ('<!doctype html><html lang="ru"><meta charset="utf-8"><title>EasyLAN — инструкция</title><style>body{max-width:950px;margin:32px auto;padding:0 20px;font:18px/1.55 system-ui;color:#203247}code{background:#edf2f6}h1,h2{color:#153858}</style>'+''.join(rows)+'</html>').encode('utf-8')
def build():
    module=ROOT/'easylan';jar=module/'build/libs'/f'easylan-fabric-26.3-{VERSION}.jar';data=jar.read_bytes()
    assert metadata(data)['id']=='easylan' and metadata(data)['version']==VERSION
    with zipfile.ZipFile(jar) as z:
        assert z.testzip() is None and not any('/test/' in name for name in z.namelist())
        assert not b'${' in z.read('fabric.mod.json')
        for entry in metadata(data).get('jars',[]):assert z.getinfo(entry['file']).file_size>0
    # Immutable pristine SkyCraft release contains the official compatible Fabric API.
    upstream=ROOT/'.tools/friend-package/cache/SkyCraft-0.1.2.zip';raw=upstream.read_bytes()
    assert sha(raw)=='1133ecde384d70d5261cbc9ba149bc7b544e1653b78846a1623b56241bb392c3'
    with zipfile.ZipFile(io.BytesIO(raw)) as z:
        with zipfile.ZipFile(io.BytesIO(z.read('SKSE/Plugins/SkyCraft/SkyCraft-Minecraft.zip'))) as b:
            api=b.read('Prism/instances/SkyCraft/.minecraft/mods/fabric-api-0.161.0+26.3.jar')
    assert hashlib.sha512(api).hexdigest()=='ed6b2586d6fde11fde8472f5a527c51e99b67026e46f94d4bfd85e7e28ce5ee299173ee16ad576ceb51f39f98d30a811086a6deb1a86a524859cc16e12da109d'
    sources={}
    for p in module.rglob('*'):
        if not p.is_file():continue
        rel=p.relative_to(module)
        if rel.parts[0] in {'.gradle','build','run'}:continue
        sources[rel.as_posix()]=p.read_bytes()
    entries={'mods/'+jar.name:data,'mods/fabric-api-0.161.0+26.3.jar':api,
             'sources.zip':zipped(sources),'LICENSE.txt':(module/'LICENSE').read_bytes(),
             'README.md':(module/'README.md').read_bytes(),'ИНСТРУКЦИЯ.html':guide((module/'README.md').read_text(encoding='utf-8')),
             'UPSTREAM.json':(module/'UPSTREAM.json').read_bytes(),
             'THIRD-PARTY-NOTICES.md':(module/'THIRD-PARTY-NOTICES.md').read_bytes(),
             'tools/easylan-package-helper.ps1':(ROOT/'tools/easylan-package-helper.ps1').read_text(encoding='utf-8-sig').encode('utf-8-sig')}
    for p in (module/'licenses').glob('*'):entries['licenses/'+p.name]=p.read_bytes()
    for label,action in [('УСТАНОВИТЬ.cmd','Install'),('СОБРАТЬ-ОТЧЕТ.cmd','Report')]:
        entries[label]=('@echo off\r\npowershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\\easylan-package-helper.ps1" -Action '+action+' %*\r\nif errorlevel 1 pause\r\n').encode('ascii')
    entries['НАЧАТЬ.txt']=('EasyLAN для Minecraft 26.3 / Fabric / Java 25\r\n1. Закрыть Minecraft; распаковать весь ZIP вне Skyrim.\r\n2. Открыть ИНСТРУКЦИЯ.html.\r\n3. УСТАНОВИТЬ.cmd -> папка Minecraft из Prism.\r\n4. Мир -> Esc -> EasyLAN: LAN -> Проверка сессии OFFLINE -> Открыть LAN.\r\n5. Выбрать Radmin -> Копировать IP:порт -> передать клиенту.\r\n6. Отчёт: кнопка в меню либо СОБРАТЬ-ОТЧЕТ.cmd.\r\n').encode('utf-8-sig')
    mods=[{'id':metadata(v)['id'],'version':metadata(v)['version'],'file':k,'sha256':sha(v)} for k,v in entries.items() if k.startswith('mods/')]
    entries['package-manifest.json']=(json.dumps({'schema':1,'kind':'easylan-standalone','version':VERSION,'minecraft':'26.3','mods':mods},indent=2)+'\n').encode('utf-8')
    entries['SHA256.txt']=''.join(sha(v)+'  '+k+'\n' for k,v in sorted(entries.items())).encode('utf-8')
    dest=ROOT/'dist'/f'EasyLAN-Fabric-26.3-{VERSION}.zip'
    if dest.exists():raise FileExistsError('Preserve existing release package')
    dest.write_bytes(zipped(entries));dest.with_suffix('.zip.sha256').write_text(sha(dest.read_bytes())+'  '+dest.name+'\n')
    print(json.dumps({'archive':str(dest),'bytes':dest.stat().st_size,'sha256':sha(dest.read_bytes())}));return dest
if __name__=='__main__':build()
