"""Windows integration acceptance of the distributable friend kit on isolated fixtures."""
import argparse
import ctypes
from ctypes import wintypes
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT=Path(__file__).resolve().parents[1]
SECRET='SYNTHETIC_FRIEND_SECRET_NEVER_EXPORT_71'

def fake_mod(mod_id,version):
    data=io.BytesIO()
    with zipfile.ZipFile(data,'w') as z:z.writestr('fabric.mod.json',json.dumps({'id':mod_id,'version':version,'authors':[SECRET]}))
    return data.getvalue()

def run(script,*arguments,ok=True):
    result=subprocess.run(['powershell.exe','-NoProfile','-ExecutionPolicy','Bypass','-File',str(script),*map(str,arguments)],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=120)
    if ok and result.returncode:raise RuntimeError(result.stdout.decode('utf-8',errors='replace'))
    if not ok and not result.returncode:raise AssertionError('Expected installer failure')
    return result

def locked_file(path):
    kernel=ctypes.WinDLL('kernel32',use_last_error=True)
    kernel.CreateFileW.argtypes=[wintypes.LPCWSTR,wintypes.DWORD,wintypes.DWORD,ctypes.c_void_p,wintypes.DWORD,wintypes.DWORD,wintypes.HANDLE]
    kernel.CreateFileW.restype=wintypes.HANDLE
    kernel.CloseHandle.argtypes=[wintypes.HANDLE]
    # Allow reading metadata, but deny DELETE sharing so Move-Item fails.
    handle=kernel.CreateFileW(str(path),0x80000000,1,None,3,0,None)
    if handle==ctypes.c_void_p(-1).value:raise ctypes.WinError(ctypes.get_last_error())
    return kernel,handle

def test(archive,skyrim_directory):
    fixtures=ROOT/'.tools/friend-tests';fixtures.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='fixture-',dir=fixtures) as temporary:
        base=Path(temporary);assert base.resolve().is_relative_to(ROOT)
        kit=base/'kit';kit.mkdir()
        with zipfile.ZipFile(archive) as z:
            assert z.testzip() is None
            for entry in z.infolist():
                target=(kit/entry.filename).resolve();assert target.is_relative_to(kit.resolve())
            z.extractall(kit)
        manifest=json.loads((kit/'package-manifest.json').read_text())
        for line in (kit/'SHA256.txt').read_text(encoding='utf-8').splitlines():
            expected,name=line.split('  ',1);assert hashlib.sha256((kit/name).read_bytes()).hexdigest()==expected,name
        with zipfile.ZipFile(kit/manifest['mo2Archive']['file']) as mod:
            assert hashlib.sha256(mod.read('SKSE/Plugins/SkyCraft.dll')).hexdigest()==manifest['nativeDll']['sha256']
            ini=mod.read('SKSE/Plugins/SkyCraft.ini');assert b'sLauncher =\r\n' in ini or b'sLauncher =\n' in ini
            with zipfile.ZipFile(io.BytesIO(mod.read('SKSE/Plugins/SkyCraft/SkyCraft-Minecraft.zip'))) as bundle:
                assert not any('/mods/e4mc-' in name for name in bundle.namelist())
                assert not any('/saves/' in name or '/logs/' in name or name.endswith('accounts.json') for name in bundle.namelist())
                assert sum('/mods/skycraft-' in name for name in bundle.namelist())==1
                native=mod.read('SKSE/Plugins/SkyCraft.dll')
                api=bundle.read('Prism/instances/SkyCraft/.minecraft/mods/fabric-api-0.161.0+26.3.jar')
        with zipfile.ZipFile(kit/manifest['prismInstance']['file']) as instance:
            names=instance.namelist();assert 'SkyCraft-YS/instance.cfg' in names and 'SkyCraft-YS/mmc-pack.json' in names
        game=base/'instance/.minecraft'
        for folder in ['mods','config','saves/test-world','logs/skycraft-network']:(game/folder).mkdir(parents=True,exist_ok=True)
        (game/'config/skycraft.properties').write_text('network.proxy.password='+SECRET,encoding='utf-8')
        (game/'accounts.json').write_text(SECRET)
        (game/'saves/test-world/level.dat').write_text(SECRET)
        (game/'logs/latest.log').write_text(SECRET)
        (game.parent/'mmc-pack.json').write_text(json.dumps({'components':[{'uid':'net.minecraft','version':'26.3'},{'uid':'net.fabricmc.fabric-loader','version':'0.19.5'}]}))
        (game/'mods/fabric-api.jar').write_bytes(api)
        old=game/'mods/skycraft-original.jar';old.write_bytes(fake_mod('skycraft','0.1.2'))
        e4mc=game/'mods/renamed-old-e4mc.jar';e4mc.write_bytes(fake_mod('e4mc','6.2.2'))
        preserved={name:(game/name).read_bytes() for name in ['accounts.json','config/skycraft.properties','saves/test-world/level.dat','logs/latest.log']}
        report_dir=base/'reports'
        if not skyrim_directory:
            skyrim_directory=base/'fake-skyrim';skyrim_directory.mkdir();(skyrim_directory/'SkyrimSE.exe').write_bytes(b'fixture')
        check=kit/'tools/check-friend-installation.ps1'
        def preflight():
            run(check,'-GameDirectory',game,'-SkyrimDirectory',skyrim_directory,'-OutputDirectory',report_dir)
            path=max(report_dir.glob('installation-*'),key=lambda p:p.stat().st_mtime)
            if path.suffix=='.zip':
                with zipfile.ZipFile(path) as z:return json.loads(z.read('installation.json').decode('utf-8-sig'))
            return json.loads((path/'installation.json').read_text(encoding='utf-8-sig'))
        facts=preflight();assert any(c['Id']=='skycraft_version' and c['Status']=='FAIL' for c in facts['Checks'])
        install=kit/'tools/install-friend-update.ps1'
        run(install,'-GameDirectory',game)
        destination=game/'mods'/manifest['fabricJar']['file']
        assert hashlib.sha256(destination.read_bytes()).hexdigest()==manifest['fabricJar']['sha256']
        assert not old.exists() and not e4mc.exists()
        backups=list((game.parent/'SkyCraft-YS-backups').glob('*/installation.json'));assert len(backups)==1
        run(install,'-GameDirectory',game);assert len(list((game.parent/'SkyCraft-YS-backups').glob('*/installation.json')))==1,'Reinstall should be a no-op'
        facts=preflight();assert all(c['Status']=='PASS' for c in facts['Checks'] if c['Id'] in ('skycraft_version','e4mc','fabric_api','minecraft','fabric_loader'))
        duplicate=game/'mods/renamed-duplicate.jar';duplicate.write_bytes(fake_mod('skycraft','old'))
        facts=preflight();assert any(c['Id']=='skycraft_count' and c['Status']=='FAIL' for c in facts['Checks']);duplicate.unlink()
        for name,data in preserved.items():assert (game/name).read_bytes()==data,name
        # Force a failure after one old JAR has moved: the other one is locked.
        destination.unlink();a=game/'mods/a-e4mc.jar';b=game/'mods/b-skycraft.jar'
        a.write_bytes(fake_mod('e4mc','old'));b.write_bytes(fake_mod('skycraft','old'));before={p.name:p.read_bytes() for p in (a,b)}
        kernel,handle=locked_file(b)
        try:run(install,'-GameDirectory',game,ok=False)
        finally:kernel.CloseHandle(handle)
        assert not destination.exists()
        assert all((game/'mods'/name).read_bytes()==data for name,data in before.items()),'Rollback must restore every moved JAR'
        run(install,'-GameDirectory',game)
        events=[{'schema':1,'utc':'2026-10-03T12:00:00Z','run_id':'AUTO','role':'auto','event':'sample','skyrim_linked':True},{'schema':1,'utc':'2026-10-03T12:00:01Z','run_id':'AUTO','role':'auto','event':'trace_closed','dropped_events':0}]
        trace=game/'logs/skycraft-network/AUTO-auto-fixture.jsonl';trace.write_text('\n'.join(json.dumps(e) for e in events)+'\n')
        native_log=base/'SkyCraft.log';native_log.write_text('[12:00] SkyCraft 0.1.2 loading (runtime 1-7-104-0)\nshared memory Local\\SkyCraft_v1 (created)\ngame hooks installed\nreceived Minecraft texture atlas\nMinecraft now drives camera rotation\n'+SECRET,encoding='utf-8')
        run(kit/'tools/collect-network-report.ps1','-GameDirectory',game,'-RunId','STARTUP','-Role','Client','-Result','partial','-SkseLog',native_log,'-IncludeLatestAutoTrace','-OutputDirectory',report_dir)
        with zipfile.ZipFile(next(report_dir.glob('STARTUP-client-*.zip'))) as report:
            summary=json.loads(report.read('summary.json').decode('utf-8-sig'));assert summary['AutoTraceFallback'] and summary['Events']==2 and summary['TraceStopped']
            native_summary=json.loads(report.read('skyrim-startup.json').decode('utf-8-sig'));assert native_summary['Runtime']=='1-7-104-0' and native_summary['CameraControlledByMinecraft']
            assert not any(n.endswith('.log') or 'accounts' in n or 'properties' in n for n in report.namelist())
            assert all(SECRET.encode() not in report.read(n) for n in report.namelist()),'Sensitive synthetic data leaked'
        # A non-executable fixture directory suffices to exercise the bounded path scanner.
        fake=base/'long-skyrim';fake.mkdir();(fake/'SkyrimSE.exe').write_bytes(b'fixture')
        long_folder=fake/'Mods'/('a'*60)/('b'*60)/('c'*60)/('d'*60)/('e'*30)
        long_folder.mkdir(parents=True);(long_folder/'cache.pom').write_text('fixture')
        run(check,'-GameDirectory',game,'-SkyrimDirectory',fake,'-OutputDirectory',base/'long-reports')
        long_json=next((base/'long-reports').glob('*/installation.json'))
        long_facts=json.loads(long_json.read_text(encoding='utf-8-sig'))
        assert any(c['Id']=='game_paths' and c['Status']=='FAIL' for c in long_facts['Checks']),long_facts
        print('FRIEND KIT PASS: checksums/nested runtime ZIPs, isolated update/backup/no-op, renamed duplicate detection, rollback on locked JAR, preserved config/world/account, AUTO/native summary, no secret export, long-path detection')

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('archive',type=Path);parser.add_argument('--skyrim-directory',type=Path);args=parser.parse_args();test(args.archive,args.skyrim_directory)
