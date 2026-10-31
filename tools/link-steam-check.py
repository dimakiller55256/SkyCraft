"""SkyCraft Link with real Steam on one account: the host side only (a guest needs a second account).
Run it only with the owner's consent: it uses their Steam account, shows them "in Spacewar" and opens a
friends-only lobby for a few seconds. Steam must already be running and logged in.

Checks: a usable steam_api64.dll; Steam starts for Spacewar; a lobby opens; the friends list can be
read while hosting; "join auto" finds no friend's world (ours is not a friend's) and says so; asked
to stop, the host leaves its lobby and ends cleanly. The console output leaves out names and IDs.

Usage: python link-steam-check.py <SkyCraftLink.exe> <report dir>
"""
import json
import os
import subprocess
import sys
import threading
import time

PRIVATE = ("self", "name", "lobby", "id", "host", "path", "dll")


def public(event):
    return {k: ("…" if k in PRIVATE else v) for k, v in event.items()}


class Link:
    def __init__(self, exe, name, args, log, stdin=False):
        self.name, self.events, self.log = name, [], log
        self.proc = subprocess.Popen([exe] + args, stdin=subprocess.PIPE if stdin else subprocess.DEVNULL,
                                     stdout=subprocess.PIPE, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        for raw in self.proc.stdout:
            line = raw.decode("utf-8", "replace").rstrip()
            self.log.append(f"{self.name}: {line}")
            try:
                self.events.append(json.loads(line))
            except ValueError:
                pass

    def wait(self, test, timeout):
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            for event in list(self.events):
                if test(event):
                    return event
            if self.proc.poll() is not None and not any(test(e) for e in self.events):
                time.sleep(0.2)  # the reader may still be catching up
                return next((e for e in self.events if test(e)), None)
            time.sleep(0.05)
        return None

    def code(self, timeout):
        try:
            return self.proc.wait(timeout)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            return None


def main():
    exe, report = sys.argv[1], sys.argv[2]
    os.makedirs(report, exist_ok=True)
    log, checks, links = [], [], []

    def check(name, ok, detail=""):
        checks.append({"check": name, "ok": bool(ok), "detail": detail})
        print(("PASS " if ok else "FAIL ") + name + (f" ({detail})" if detail else ""), flush=True)
        return ok

    def errors(link):
        return [public(e) for e in link.events if e.get("event") == "error"]

    try:
        probe = Link(exe, "probe", ["probe"], log)
        probe.code(30)
        result = probe.wait(lambda e: e.get("event") == "probe", 1) or {}
        if not check("usable steam_api64.dll, Steam running", result.get("usable") and result.get("steam_running"), json.dumps(public(result), ensure_ascii=False)):
            return 1

        host = Link(exe, "host", ["host", "--minecraft-port", "25565", "--no-together", "--stdin-control"], log, stdin=True)
        links.append(host)
        started = time.monotonic()
        steam = host.wait(lambda e: e.get("event") in ("steam", "error"), 30)
        if not check("Steam started for Spacewar", steam and steam.get("event") == "steam", json.dumps(errors(host), ensure_ascii=False)):
            return 1
        hosting = host.wait(lambda e: e.get("event") in ("hosting", "error"), 30)
        check("friends-only lobby opened", hosting and hosting.get("event") == "hosting",
              f"{time.monotonic() - started:.1f} s after start" if hosting and hosting.get("event") == "hosting" else json.dumps(errors(host), ensure_ascii=False))

        friends = Link(exe, "friends", ["friends", "--wait-ms", "4000"], log)
        links.append(friends)
        code = friends.code(40)
        listed = friends.wait(lambda e: e.get("event") == "friends", 1)
        check("friends list read while hosting", listed is not None and code == 0,
              f"exit {code}, SkyCraft hosts {len(listed.get('hosts', []))}, other Spacewar lobbies {listed.get('others')}" if listed else f"exit {code}")

        join = Link(exe, "join", ["join", "auto", "--wait-ms", "4000", "--no-together"], log)
        links.append(join)
        code = join.code(40)
        refused = join.wait(lambda e: e.get("event") == "error", 1)
        check("join auto says no friend has a world open", code == 3 and refused and refused.get("code") in ("no_hosts", "several_hosts"),
              f"exit {code}, {refused.get('code') if refused else 'no error event'}")

        host.proc.stdin.write(b"stop\n")
        host.proc.stdin.flush()
        code = host.code(10)
        stopped = host.wait(lambda e: e.get("event") == "stopped", 1)
        check("host leaves its lobby and ends when asked", code == 0 and stopped is not None, f"exit {code}")
        check("no errors from the host", not errors(host), json.dumps(errors(host), ensure_ascii=False))
    finally:
        for link in links:
            if link.proc.poll() is None:
                link.proc.kill()
        with open(os.path.join(report, "link-steam-check.log"), "w", encoding="utf-8") as f:
            f.write("\n".join(log))  # local only: has the account's name and IDs
        with open(os.path.join(report, "link-steam-check.json"), "w", encoding="utf-8") as f:
            json.dump({"checks": checks}, f, ensure_ascii=False, indent=1)
    failed = [c for c in checks if not c["ok"]]
    print(f"{len(checks) - len(failed)}/{len(checks)} checks passed", flush=True)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
