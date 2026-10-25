"""Run exported Loom clients after Gradle exits, with bounded time and local reports.
With --steamlink <SkyCraftLink.exe> both clients start SkyCraft Link from the mod's Steam menu and
the guest joins through it, over the program's loopback link instead of Steam.
With --steam-host only the host runs: real Steam on the owner's account (ask first), the program
unpacked from the mod, a friends-only lobby opened from the Steam menu, nothing left running after."""
import json, os, socket, subprocess, time, shutil, sys
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
def free_port():
    with socket.socket() as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]
def main():
    audit=Path(sys.argv[1]); audit.mkdir(parents=True,exist_ok=True)
    link=sys.argv[sys.argv.index('--steamlink')+1] if '--steamlink' in sys.argv else None
    steam_host='--steam-host' in sys.argv
    env={}; seen=set()
    for key,value in os.environ.items():
        if key.upper() not in seen:env[key]=value;seen.add(key.upper())
    temporary=ROOT/'.tools/tmp';temporary.mkdir(exist_ok=True)
    env['TEMP']=str(temporary);env['TMP']=str(temporary)
    jobs=[]; logs=[]
    # The clients' links meet on this port (SkyCraft Link's --loopback) instead of in a Steam lobby.
    link_args=['-Dskycraft.gameplayJoin=steamlink',f'-Dskycraft.linkExe={link}',f'-Dskycraft.linkExtraArgs=--loopback {free_port()} --no-together'] if link else []
    if steam_host:link_args=['-Dskycraft.gameplayJoin=steamhost']
    started=time.time()
    for role,task in [('Host','runGameplayHostClient')]+([] if steam_host else [('Guest','runGameplayGuestClient')]):
        data=json.loads((ROOT/'.tools'/f'{task}.json').read_text(encoding='utf-8'))
        game=Path(data['workingDirectory'])
        # These are owned isolated test directories, never Prism instances.
        assert game.resolve()==ROOT/'.tools'/('gameplay-host' if role=='Host' else 'gameplay-client')
        game.mkdir(exist_ok=True)
        for name in ('port.txt','smoke-result.txt','dug.txt','server-dug.txt','combat.txt','rejoined.txt','shield-done.txt','ownership.txt','actor-hit.txt','input-check.txt','collision-check.txt','items.txt','item-packet.txt'):(game/name).unlink(missing_ok=True)
        (game/'options.txt').write_text('lang:ru_ru\nguiScale:2\nonboardAccessibility:false\nsoundCategory_master:0.0\nrenderDistance:3\nsimulationDistance:5\nmaxFps:30\npauseOnLostFocus:false\n',encoding='utf-8')
        log=(audit/f'{role}-game.log').open('w',encoding='utf-8');logs.append(log)
        p=subprocess.Popen([data['java'],*link_args,*data['args']],cwd=game,env=env,stdout=log,stderr=subprocess.STDOUT,
                           creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
        jobs.append((role,game,p))
    print('Two isolated SkyCraft gameplay clients running'+(' with SkyCraft Link' if link else '')+'; audio disabled.',flush=True)
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
        for name in ('ownership.txt','actor-hit.txt','input-check.txt','combat.txt','collision-check.txt','items.txt','item-packet.txt'):
            if (game/name).exists():shutil.copyfile(game/name,audit/f'{role}-{name}')
        print(f'{role}: {result}',flush=True)
        failed |= p.returncode!=0 or not result.startswith('PASS')
        for folder in ('gameplay-reports','screenshots'):
            for file in (game/folder).glob('*'):
                if file.is_file() and file.stat().st_mtime >= started-1:
                    shutil.copyfile(file,audit/f'{role}-{file.name}')
    for log in logs:log.close()
    if steam_host:
        # The program must end with the game: a stray one would keep the lobby open.
        left=None
        for _ in range(20):
            out=subprocess.run(['powershell','-NoProfile','-Command',"(Get-Process -Name 'SkyCraftLink-*' -ErrorAction SilentlyContinue | Measure-Object).Count"],capture_output=True,text=True)
            left=out.stdout.strip()
            if left=='0':break
            time.sleep(0.5)
        print(f'SkyCraft Link processes left after the game closed: {left}',flush=True)
        failed |= left!='0'
    if link:
        # The links' own events, as each client logged them: the guest really came through one.
        host_log=(audit/'Host-game.log').read_text(encoding='utf-8',errors='replace')
        guest_log=(audit/'Guest-game.log').read_text(encoding='utf-8',errors='replace')
        joined='"state":"joined"' in host_log
        handed='"event":"listening","service":"minecraft"' in guest_log
        print(f'SkyCraft Link: host saw the guest join: {joined}; guest link opened its Minecraft port: {handed}',flush=True)
        failed |= not joined or not handed
    if failed:raise SystemExit(1)
    if steam_host:print('PASS: real Minecraft host opened a friends-only Steam lobby from the Steam menu (real Steam; no guest).',flush=True)
    elif link:print('PASS: real Minecraft PLAY and two players through SkyCraft Link from the Steam menu (its loopback link, not Steam).',flush=True)
    else:print('PASS: real Minecraft PLAY and two players. Connectivity between PCs remains untested.',flush=True)
if __name__=='__main__':main()
