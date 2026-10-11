"""Verify shipped hashes, repeat installation, rollback preflight and data-preserving reports."""
from pathlib import Path
import json,zipfile,hashlib,subprocess,tempfile,sys
ROOT=Path(__file__).resolve().parents[1]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def run(args,ok=True):
    r=subprocess.run(args,capture_output=True)
    if (r.returncode==0)!=ok:raise RuntimeError(r.stdout.decode('utf-8',errors='replace')+r.stderr.decode('utf-8',errors='replace'))
def main():
    archive=Path(sys.argv[1]);base=Path(tempfile.mkdtemp(prefix='fixture-',dir=ROOT/'.tools/easylan-package-tests'))
    kit=base/'kit';game=base/'minecraft';kit.mkdir();game.mkdir();(game/'mods').mkdir();(game/'config').mkdir()
    checks=[]
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None
        for line in z.read('SHA256.txt').decode().splitlines():
            expected,name=line.split('  ',1);assert hashlib.sha256(z.read(name)).hexdigest()==expected,name
        z.extractall(kit)
    checks.append('PACKAGE_CRC_AND_HASHES PASS')
    protected={'accounts.json':b'fixture-account-sentinel','config/easylan.cfg':b'online-mode=true\n',
               'saves/test/level.dat':b'fixture-world-sentinel','logs/latest.log':b'fixture-game-log-sentinel'}
    for name,data in protected.items():p=game/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
    for mod in ['easylan','fabric-api']:
        with zipfile.ZipFile(game/'mods'/f'{mod}-old.jar','w') as z:z.writestr('fabric.mod.json',json.dumps({'id':mod,'version':'old'}))
    helper=kit/'tools/easylan-package-helper.ps1'
    command=['powershell.exe','-NoProfile','-ExecutionPolicy','Bypass','-File',str(helper),'-GameDirectory',str(game),'-PackageDirectory',str(kit),'-NonInteractive']
    run(command);manifest=json.loads((kit/'package-manifest.json').read_text())
    for file in manifest['mods']:assert sha(game/'mods'/Path(file['file']).name)==file['sha256']
    for name,data in protected.items():assert (game/name).read_bytes()==data
    assert len(list((game/'mods').glob('*.jar')))==2
    assert len(list((game/'EasyLAN-backups').rglob('*old.jar')))==2
    checks.append('UPDATE_BACKUP_ACCOUNTS_CONFIG_WORLDS_PRESERVED PASS')
    run(command);assert len(list((game/'mods').glob('*.jar')))==2
    checks.append('REPEATED_INSTALLATION_NO_DUPLICATE_MODS PASS')
    old_hashes={p.name:sha(p) for p in (game/'mods').glob('*.jar')}
    target=kit/manifest['mods'][0]['file'];raw=target.read_bytes();target.write_bytes(raw+b'bad checksum')
    run(command,ok=False);assert old_hashes=={p.name:sha(p) for p in (game/'mods').glob('*.jar')};target.write_bytes(raw)
    checks.append('DAMAGED_PACKAGE_REJECTED_BEFORE_MOD_CHANGES PASS')
    reportdir=game/'easylan-reports';reportdir.mkdir();(reportdir/'fixture-events.jsonl').write_text('{"event":"test"}\n')
    run(command+['-Action','Report'])
    reports=list(reportdir.glob('*.zip'));assert len(reports)==1
    with zipfile.ZipFile(reports[0]) as z:
        assert z.testzip() is None and 'events.jsonl' in z.namelist() and 'environment.json' in z.namelist()
        assert not any(name.startswith(('accounts','saves','logs')) for name in z.namelist())
        d=json.loads(z.read('environment.json').decode('utf-8-sig'));assert d['eventsCollected']
    checks.append('REPORT_CREATED_WITHOUT_ACCOUNTS_WORLDS_FULL_GAME_LOGS PASS')
    (base/'result.json').write_text(json.dumps({'result':'PASS','checks':checks},indent=2))
    print(json.dumps({'result':'PASS','checks':checks,'report':str(base/'result.json')}))
if __name__=='__main__':
    (ROOT/'.tools/easylan-package-tests').mkdir(exist_ok=True)
    main()
