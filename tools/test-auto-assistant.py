"""Exercise the actual distributable EXE and its installer/collector worker on fixtures."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile
from datetime import datetime,timezone,timedelta
ROOT=Path(__file__).resolve().parents[1]
SECRET='SYNTHETIC_ASSISTANT_SECRET_NEVER_EXPORT_62'

def run(args,ok=True):
    result=subprocess.run(list(map(str,args)),capture_output=True,timeout=120)
    if bool(result.returncode==0)!=ok:raise AssertionError(result.stdout.decode('utf-8',errors='replace')+result.stderr.decode('utf-8',errors='replace'))

def test(archive):
    fixtures=ROOT/'.tools/assistant-tests';fixtures.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='fixture-',dir=fixtures) as temporary:
        base=Path(temporary);assert base.resolve().is_relative_to(ROOT)
        kit=base/'kit';kit.mkdir()
        with zipfile.ZipFile(archive) as z:
            assert z.testzip() is None
            for name in z.namelist():assert (kit/name).resolve().is_relative_to(kit.resolve())
            z.extractall(kit)
            for line in z.read('SHA256.txt').decode('utf-8').splitlines():
                expected,name=line.split('  ',1);assert hashlib.sha256(z.read(name)).hexdigest()==expected
        exe=kit/'SkyCraft-Без-друга.exe';result_file=base/'exe-tests.json'
        run([exe,'--self-test',result_file]);assert json.loads(result_file.read_text())['result']=='PASS'
        manifest=json.loads((kit/'package-manifest.json').read_text())
        game=base/'an instance with spaces/.minecraft'
        for folder in ['mods','config','saves/world','logs/skycraft-network']:(game/folder).mkdir(parents=True,exist_ok=True)
        with zipfile.ZipFile(game/'mods/old.jar','w') as z:z.writestr('fabric.mod.json',json.dumps({'id':'skycraft','version':'0.1.2'}))
        for path in ['config/skycraft.properties','accounts.json','saves/world/level.dat','logs/latest.log']:(game/path).write_text(SECRET,encoding='utf-8')
        properties='join=\nnetwork.mode=DIRECT\nnetwork.proxy.password='+SECRET+'\n'
        (game/'config/skycraft.properties').write_text(properties,encoding='utf-8')
        before={p:(game/p).read_bytes() for p in ['config/skycraft.properties','accounts.json','saves/world/level.dat','logs/latest.log']}
        plan=base/'plan with spaces.json';worker=kit/'tools/assistant-worker.ps1'
        wrapper=base/'fixture-worker.ps1'
        # A real Skyrim may be running in another task. Never close it or update
        # its instance. Mock only the global install guard for this proven fixture;
        # production safety checks and real process/network collection stay intact.
        wrapper.write_text('''param([string]$WorkerPath,[string]$PlanFile,[switch]$FailInstall)
function Get-Process {
    [CmdletBinding()]param([string[]]$Name)
    if ($Name.Count -eq 1 -and $Name[0] -eq 'SkyrimSE') { return }
    Microsoft.PowerShell.Management\\Get-Process @PSBoundParameters
}
function Move-Item {
    [CmdletBinding()]param([string]$LiteralPath,[string]$Destination)
    if ($FailInstall -and $LiteralPath -match '\\.installing-' -and $Destination -match '\\.jar$') { throw 'Synthetic final JAR move failure' }
    Microsoft.PowerShell.Management\\Move-Item @PSBoundParameters
}
& $WorkerPath -PlanFile $PlanFile
''',encoding='utf-8-sig')
        assert game.resolve().is_relative_to(base.resolve())
        def command():return ['powershell.exe','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',wrapper,'-WorkerPath',worker,'-PlanFile',plan]
        native=base/'MO2 test mod/SKSE/Plugins/SkyCraft.dll';native.parent.mkdir(parents=True)
        native.write_bytes(b'MZ'+b'original-native-fixture')
        original_native=native.read_bytes()
        plan.write_text(json.dumps({'action':'install','game':str(game),'nativeDll':str(native)}),encoding='utf-8')
        run(command())
        installed=game/'mods'/manifest['fabricJar']['file'];assert hashlib.sha256(installed.read_bytes()).hexdigest()==manifest['fabricJar']['sha256']
        if manifest['nativeDll']['changed']:
            assert hashlib.sha256(native.read_bytes()).hexdigest()==manifest['nativeDll']['sha256']
            backups=list((game.parent/'SkyCraft-YS-backups').glob('*/SkyCraft.dll'))
            assert len(backups)==1 and backups[0].read_bytes()==original_native
            saved=native.read_bytes();pack_native=kit/'native/SkyCraft.dll';pack_native.write_bytes(b'broken')
            run(command(),ok=False)
            assert native.read_bytes()==saved and hashlib.sha256(installed.read_bytes()).hexdigest()==manifest['fabricJar']['sha256']
            with zipfile.ZipFile(archive) as z:pack_native.write_bytes(z.read('native/SkyCraft.dll'))
            run(command()) # idempotent update
            native.write_bytes(original_native)
            run([*command(),'-FailInstall'],ok=False)
            assert native.read_bytes()==original_native,'Native rollback lost the old DLL'
            assert hashlib.sha256(installed.read_bytes()).hexdigest()==manifest['fabricJar']['sha256'],'JAR rollback failed'
            run(command())
        assert all((game/p).read_bytes()==value for p,value in before.items())
        output=base/'reports with spaces'
        native_logs=base/'SKSE logs';native_logs.mkdir()
        (native_logs/'SkyCraft.log').write_text('SkyCraft synthetic crash stack and frame diagnostics',encoding='utf-8')
        (native_logs/'SkyCraft_crash.dmp').write_bytes(b'MDMP-local-fixture')
        run(['powershell.exe','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',kit/'tools/collect-network-report.ps1','-GameDirectory',game,'-RunId','GAMEPLAY_FIXTURE','-Role','Client','-OutputDirectory',output,'-SkseLog',native_logs/'SkyCraft.log','-IncludeGameplayDiagnostics'])
        with zipfile.ZipFile(next(output.glob('GAMEPLAY_FIXTURE-client-*.zip'))) as z:
            assert z.read('gameplay/SkyCraft_crash.dmp')==b'MDMP-local-fixture'
            assert z.read('gameplay/SkyCraft.log')==b'SkyCraft synthetic crash stack and frame diagnostics'
            assert SECRET.encode() not in z.read('gameplay/minecraft-gameplay.log')
            assert not any('accounts' in n or 'properties' in n for n in z.namelist())
            index=json.loads(z.read('gameplay-files.json').decode('utf-8-sig'));assert all(row['Copied'] for row in index)
        run(['powershell.exe','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',kit/'tools/collect-network-report.ps1','-GameDirectory',game,'-RunId','PREVIOUS_DUMP','-Role','Client','-OutputDirectory',output,'-SkseLog',native_logs/'SkyCraft.log','-IncludeGameplayDiagnostics','-StartedUtc',(datetime.now(timezone.utc)+timedelta(minutes=1)).isoformat()])
        with zipfile.ZipFile(next(output.glob('PREVIOUS_DUMP-client-*.zip'))) as z:
            assert not any(n.endswith('.dmp') for n in z.namelist()),'A previous dump was mislabeled as this run'
            index=json.loads(z.read('gameplay-files.json').decode('utf-8-sig'))
            assert any(row['Name'].endswith('.dmp') and not row['Copied'] and not row['SinceRunStarted'] for row in index)
        environment=base/'environment.json'
        plan.write_text(json.dumps({'action':'environment','output':str(environment),'targets':['127.0.0.1']}),encoding='utf-8')
        run(command());snapshot=json.loads(environment.read_text(encoding='utf-8-sig'))
        assert snapshot['schema']==1 and snapshot['selectedRoutes'] and not snapshot['collectionErrors'],snapshot
        assert 'CommandLine' not in environment.read_text(encoding='utf-8-sig') and SECRET not in environment.read_text(encoding='utf-8-sig')
        events=[{'schema':1,'utc':'2026-10-04T14:00:00Z','run_id':'AUTO_TEST','role':'client','event':'sample','skyrim_linked':False},
                {'schema':1,'utc':'2026-10-04T14:00:01Z','run_id':'AUTO_TEST','role':'client','event':'trace_closed','dropped_events':0}]
        (game/'logs/skycraft-network/AUTO_TEST-client-fixture.jsonl').write_text('\n'.join(json.dumps(e) for e in events)+'\n',encoding='utf-8')
        (game/'logs/skycraft-network/AUTO_TEST-client-older.jsonl').write_text(json.dumps(events[0])+'\n',encoding='utf-8')
        output=base/'reports with spaces'
        plan.write_text(json.dumps({'action':'collect','game':str(game),'skyrim':'NOT_FOUND','mods':'NOT_FOUND','output':str(output),'runId':'AUTO_TEST','role':'client','network':'Loopback','target':'127.0.0.1:1','traceFile':'AUTO_TEST-client-fixture.jsonl'}),encoding='utf-8')
        run(command())
        with zipfile.ZipFile(next(output.glob('AUTO_TEST-client-*.zip'))) as z:
            summary=json.loads(z.read('summary.json').decode('utf-8-sig'))
            assert summary['TraceStopped'] and summary['Events']==2
            assert summary['CollectionErrors']==0,summary
            assert all(SECRET.encode() not in z.read(n) for n in z.namelist())
            assert not any(n.endswith('.log') or 'accounts' in n or 'properties' in n for n in z.namelist())
        assert all((game/p).read_bytes()==value for p,value in before.items())
        plan.write_text(json.dumps({'action':'exec','game':str(game)}));run(command(),ok=False)
        print('AUTO ASSISTANT PASS: package hashes; EXE self-checks; JAR+DLL backup/idempotence/tamper rejection/rollback; preserved accounts/config/world; network and gameplay ZIP with local dump; zero environment collection errors; no synthetic secrets; unknown action rejected')

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('archive',type=Path);args=parser.parse_args();test(args.archive)
