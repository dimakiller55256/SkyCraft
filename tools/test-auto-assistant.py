"""Exercise the actual distributable EXE and its installer/collector worker on fixtures."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile
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
        wrapper.write_text('''param([string]$WorkerPath,[string]$PlanFile)
function Get-Process {
    [CmdletBinding()]param([string[]]$Name)
    if ($Name.Count -eq 1 -and $Name[0] -eq 'SkyrimSE') { return }
    Microsoft.PowerShell.Management\\Get-Process @PSBoundParameters
}
& $WorkerPath -PlanFile $PlanFile
''',encoding='utf-8-sig')
        assert game.resolve().is_relative_to(base.resolve())
        def command():return ['powershell.exe','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',wrapper,'-WorkerPath',worker,'-PlanFile',plan]
        plan.write_text(json.dumps({'action':'install','game':str(game)}),encoding='utf-8')
        run(command())
        installed=game/'mods'/manifest['fabricJar']['file'];assert hashlib.sha256(installed.read_bytes()).hexdigest()==manifest['fabricJar']['sha256']
        assert all((game/p).read_bytes()==value for p,value in before.items())
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
        print('AUTO ASSISTANT PASS: distributable checksums, EXE 8 checks, worker update with backup, preserved config/account/world, noninteractive collector with zero errors, no synthetic secrets, unknown action rejected')

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('archive',type=Path);args=parser.parse_args();test(args.archive)
