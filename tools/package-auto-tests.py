"""Create a small GUI/update kit for an already working SkyCraft installation."""
import argparse
import hashlib
import json
from pathlib import Path
import importlib.util
import zipfile
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('friend_package',ROOT/'tools/package-friend-kit.py')
package=importlib.util.module_from_spec(spec);spec.loader.exec_module(package)

def build(friend, output):
    with zipfile.ZipFile(friend) as z:
        assert z.testzip() is None
        for line in z.read('SHA256.txt').decode('utf-8').splitlines():
            expected,name=line.split('  ',1)
            assert hashlib.sha256(z.read(name)).hexdigest()==expected,name
        manifest=json.loads(z.read('package-manifest.json'))
        names={'SkyCraft-Хост.exe','SkyCraft-Клиент.exe','SkyCraft-Без-друга.exe','АВТОТЕСТЫ.html','LICENSE.txt','THIRD-PARTY-NOTICES.md',manifest['fabricJar']['file']}
        names.update('tools/'+name for name in ['assistant-worker.ps1','skycraft-package-common.ps1','install-friend-update.ps1','check-friend-installation.ps1','collect-network-report.ps1','collect-test-environment.ps1'])
        names.update(name for name in z.namelist() if name.startswith('docs/'))
        names.add('ИГРОВЫЕ-ПРОВЕРКИ.html')
        if manifest['nativeDll']['changed']:names.add('native/SkyCraft.dll')
        entries={name:z.read(name) for name in names}
    manifest.pop('mo2Archive');manifest.pop('prismInstance')
    manifest['kind']='automatic-test-update';manifest['requiresExistingSkyCraft']=True
    entries['package-manifest.json']=(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n').encode('utf-8')
    entries['НАЧАТЬ.txt']=(f'Обновление и автоматические тесты SkyCraft {manifest["version"]}\r\n'
        '1. Распаковать весь ZIP вне Skyrim.\r\n'
        '2. Открыть ИГРОВЫЕ-ПРОВЕРКИ.html (актуальная инструкция).\r\n'
        '3. Выбрать SkyCraft-Хост.exe / SkyCraft-Клиент.exe / SkyCraft-Без-друга.exe.\r\n'
        '4. При закрытых Skyrim/Minecraft нажать «Обновить мод»: нужен network.6 JAR и DLL на обоих ПК. Выберите установленную SkyCraft.dll в MO2 -> SkyCraft -> SKSE -> Plugins.\r\n'
        '5. MO2/SKSE -> отдельное тестовое сохранение -> помощник -> «Игровой тест до остановки».\r\n'
        '6. Хост передаёт новый код SCY2 клиенту; в конце «Остановить и собрать отчёт» -> «Открыть отчёты».\r\n'
        'В маленьком комплекте нет полного MO2/Prism для новой установки; для неё нужен friend-kit.\r\n').encode('utf-8-sig')
    entries['SHA256.txt']=''.join(package.sha(value)+'  '+name+'\n' for name,value in sorted(entries.items())).encode('utf-8')
    output.mkdir(parents=True,exist_ok=True)
    archive=output/f'SkyCraft-AutoTests-{manifest["version"]}.zip'
    if archive.exists():raise FileExistsError('Preserve the existing kit or choose another output directory')
    archive.write_bytes(package.zip_bytes(entries))
    archive.with_suffix('.zip.sha256').write_text(package.sha(archive.read_bytes())+'  '+archive.name+'\n',encoding='utf-8')
    print(json.dumps({'archive':str(archive),'bytes':archive.stat().st_size,'files':len(entries),'sha256':package.sha(archive.read_bytes())}))
    return archive

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('friend_kit',type=Path)
    parser.add_argument('--output-directory',type=Path,default=ROOT/'dist')
    args=parser.parse_args();build(args.friend_kit,args.output_directory)
