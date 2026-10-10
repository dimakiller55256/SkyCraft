"""Run exported Loom clients after Gradle exits, with bounded time and local reports."""
import json, os, subprocess, time, shutil, sys
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
def main():
    audit=Path(sys.argv[1]); audit.mkdir(parents=True,exist_ok=True)
    env={}; seen=set()
    for key,value in os.environ.items():
        if key.upper() not in seen:env[key]=value;seen.add(key.upper())
    temporary=ROOT/'.tools/tmp';temporary.mkdir(exist_ok=True)
    env['TEMP']=str(temporary);env['TMP']=str(temporary)
    jobs=[]; logs=[]
    started=time.time()
    for role,task in [('Host','runGameplayHostClient'),('Guest','runGameplayGuestClient')]:
        data=json.loads((ROOT/'.tools'/f'{task}.json').read_text(encoding='utf-8'))
        game=Path(data['workingDirectory'])
        # These are owned isolated test directories, never Prism instances.
        assert game.resolve()==ROOT/'.tools'/('gameplay-host' if role=='Host' else 'gameplay-client')
        game.mkdir(exist_ok=True)
        for name in ('port.txt','smoke-result.txt','dug.txt','server-dug.txt','combat.txt','rejoined.txt','shield-done.txt','ownership.txt','actor-hit.txt','input-check.txt','collision-check.txt'):(game/name).unlink(missing_ok=True)
        (game/'options.txt').write_text('lang:ru_ru\nguiScale:2\nonboardAccessibility:false\nsoundCategory_master:0.0\nrenderDistance:3\nsimulationDistance:5\nmaxFps:30\npauseOnLostFocus:false\n',encoding='utf-8')
        log=(audit/f'{role}-game.log').open('w',encoding='utf-8');logs.append(log)
        p=subprocess.Popen([data['java'],*data['args']],cwd=game,env=env,stdout=log,stderr=subprocess.STDOUT,
                           creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
        jobs.append((role,game,p))
    print('Two isolated SkyCraft gameplay clients running; audio disabled.',flush=True)
    deadline=time.monotonic()+300
    failed=False
    while any(p.poll() is None for _,_,p in jobs):
        if time.monotonic()>deadline:
            # Terminate only subprocesses this runner created, using retained handles.
            for _,_,p in jobs:
                if p.poll() is None:p.terminate()
            failed=True;break
        time.sleep(1)
    for role,game,p in jobs:
        p.wait(timeout=15)
        path=game/'smoke-result.txt'
        result=path.read_text(encoding='utf-8') if path.exists() else 'FAIL: no test result'
        (audit/f'{role}-result.txt').write_text(result,encoding='utf-8')
        for name in ('ownership.txt','actor-hit.txt','input-check.txt','combat.txt','collision-check.txt'):
            if (game/name).exists():shutil.copyfile(game/name,audit/f'{role}-{name}')
        print(f'{role}: {result}',flush=True)
        failed |= p.returncode!=0 or not result.startswith('PASS')
        for folder in ('gameplay-reports','screenshots'):
            for file in (game/folder).glob('*'):
                if file.is_file() and file.stat().st_mtime >= started-1:
                    shutil.copyfile(file,audit/f'{role}-{file.name}')
    for log in logs:log.close()
    if failed:raise SystemExit(1)
    print('PASS: real Minecraft PLAY and two players. Connectivity between PCs remains untested.',flush=True)
if __name__=='__main__':main()
