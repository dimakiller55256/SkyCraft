"""SkyCraft Link as two real processes on this PC over its loopback link (no Steam, no games):
command line, JSON events, TCP and UDP forwarding, a host that starts late, a host that leaves,
and ending together with the process that started it.

Usage: python link-smoke.py <SkyCraftLink.exe> <report dir>
"""
import json
import os
import queue
import random
import socket
import subprocess
import sys
import threading
import time


def free_port(kind=socket.SOCK_STREAM):
    with socket.socket(socket.AF_INET, kind) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Link:
    """One SkyCraft Link process; its JSON lines are collected as they come."""

    def __init__(self, exe, name, args, log):
        self.name = name
        self.events = []
        self.queue = queue.Queue()
        self.log = log
        self.proc = subprocess.Popen([exe] + args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                     creationflags=subprocess.CREATE_NO_WINDOW)
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        for raw in self.proc.stdout:
            line = raw.decode("utf-8", "replace").rstrip()
            self.log.append(f"{self.name}: {line}")
            try:
                event = json.loads(line)
            except ValueError:
                event = {"event": "unparsed", "line": line}
            self.events.append(event)
            self.queue.put(event)

    def wait(self, test, timeout):
        """The first event (seen before or arriving within timeout) that passes test."""
        for event in self.events:
            if test(event):
                return event
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            try:
                event = self.queue.get(timeout=max(0.01, until - time.monotonic()))
            except queue.Empty:
                break
            if test(event):
                return event
        raise AssertionError(f"{self.name}: no matching event within {timeout} s; got {[e.get('event') for e in self.events]}")

    def exit_code(self, timeout):
        try:
            return self.proc.wait(timeout)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            raise AssertionError(f"{self.name}: did not end within {timeout} s")

    def kill(self):
        if self.proc.poll() is None:
            self.proc.kill()


class Echo:
    """Stand-ins for the host's Minecraft server (TCP) and Skyrim Together server (UDP)."""

    def __init__(self):
        self.tcp = socket.socket()
        self.tcp.bind(("127.0.0.1", 0))
        self.tcp.listen()
        self.tcp_port = self.tcp.getsockname()[1]
        self.udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.udp.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 << 20)  # as SkyCraft Link's own sockets
        self.udp.bind(("127.0.0.1", 0))
        self.udp_port = self.udp.getsockname()[1]
        self.udp_senders = set()
        threading.Thread(target=self._accept, daemon=True).start()
        threading.Thread(target=self._datagrams, daemon=True).start()

    def _accept(self):
        while True:
            try:
                conn, _ = self.tcp.accept()
            except OSError:
                return
            threading.Thread(target=self._echo, args=(conn,), daemon=True).start()

    @staticmethod
    def _echo(conn):
        with conn:
            while True:
                try:
                    data = conn.recv(65536)
                except OSError:
                    return
                if not data:
                    return
                conn.sendall(data)

    def _datagrams(self):
        while True:
            try:
                data, sender = self.udp.recvfrom(65536)
            except OSError:
                return
            self.udp_senders.add(sender[1])
            self.udp.sendto(data, sender)


def stream_echo(port, size, seed):
    data = random.Random(seed).randbytes(size)
    with socket.create_connection(("127.0.0.1", port), timeout=20) as s:
        def send():
            s.sendall(data)
        sender = threading.Thread(target=send)
        sender.start()
        back = bytearray()
        while len(back) < size:
            chunk = s.recv(65536)
            if not chunk:
                break
            back += chunk
        sender.join()
    return bytes(back) == data


def datagram_echo(port, count):
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        # All are sent before any is read: the default 64 KB buffer would drop answers here.
        s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 << 20)
        s.connect(("127.0.0.1", port))
        s.settimeout(0.2)
        sent = set()
        for i in range(count):
            payload = i.to_bytes(4, "little") + random.Random(i).randbytes(1 + (i * 53) % 1200)
            sent.add(payload)
            s.send(payload)
            if i % 25 == 0:
                time.sleep(0.005)
        got = set()
        until = time.monotonic() + 5
        while got != sent and time.monotonic() < until:
            try:
                got.add(s.recv(2048))
            except socket.timeout:
                pass
        return len(got & sent), len(sent)


def stand_in_parent():
    return subprocess.Popen([sys.executable, "-c", "import time; time.sleep(600)"], creationflags=subprocess.CREATE_NO_WINDOW)


def main():
    exe, report_dir = sys.argv[1], sys.argv[2]
    os.makedirs(report_dir, exist_ok=True)
    log, checks, links, parents = [], [], [], []

    def check(name, ok, detail=""):
        checks.append({"check": name, "ok": bool(ok), "detail": detail})
        print(("PASS " if ok else "FAIL ") + name + (f" ({detail})" if detail else ""), flush=True)

    def start(name, args, parent):
        link = Link(exe, name, args + ["--parent-pid", str(parent.pid)], log)
        links.append(link)
        return link

    try:
        echo = Echo()
        link_port = free_port()
        guest_parent, host_parent = stand_in_parent(), stand_in_parent()
        parents += [guest_parent, host_parent]

        # The guest starts first and waits; the host comes up a moment later.
        guest = start("guest", ["join", "--loopback", str(link_port), "--minecraft-port", "0", "--together-port", "0"], guest_parent)
        time.sleep(1.5)
        host = start("host", ["host", "--loopback", str(link_port), "--minecraft-port", str(echo.tcp_port),
                              "--together-port", str(echo.udp_port)], host_parent)
        host.wait(lambda e: e["event"] == "hosting", 5)
        guest.wait(lambda e: e["event"] == "connected", 10)
        mc_port = guest.wait(lambda e: e["event"] == "listening" and e.get("service") == "minecraft", 5)["port"]
        st_port = guest.wait(lambda e: e["event"] == "listening" and e.get("service") == "together", 5)["port"]
        host.wait(lambda e: e["event"] == "peer" and e.get("state") == "joined", 5)
        check("guest connected after a late host", True, f"minecraft {mc_port}, together {st_port}")

        results = []
        threads = [threading.Thread(target=lambda i=i: results.append(stream_echo(mc_port, (8 << 20) if i == 0 else 200_000 + i, i)))
                   for i in range(6)]
        started = time.monotonic()
        for t in threads:
            t.start()
        for t in threads:
            t.join(60)
        check("six TCP streams echo through the tunnel", len(results) == 6 and all(results),
              f"{len(results)} finished, {sum(results)} exact, {time.monotonic() - started:.1f} s")

        got, sent = datagram_echo(st_port, 300)
        check("UDP datagrams through the tunnel", got == sent, f"{got}/{sent}")

        # The guest's Minecraft closes: the guest's link ends, the host sees it leave.
        guest_parent.kill()
        code = guest.exit_code(5)
        check("guest ends with its parent", code == 0 and guest.events[-1].get("event") == "stopped", f"exit {code}")
        host.wait(lambda e: e["event"] == "peer" and e.get("state") == "left", 5)
        check("host sees the guest leave", True)

        # A second guest; then the host's world closes and the guest is told.
        guest_parent2 = stand_in_parent()
        parents.append(guest_parent2)
        guest2 = start("guest2", ["join", "--loopback", str(link_port), "--minecraft-port", "0", "--no-together"], guest_parent2)
        guest2.wait(lambda e: e["event"] == "connected", 10)
        mc2 = guest2.wait(lambda e: e["event"] == "listening" and e.get("service") == "minecraft", 5)["port"]
        check("second guest echo", stream_echo(mc2, 100_000, 99))
        check("--no-together opens no UDP port", not any(e.get("service") == "together" for e in guest2.events))
        host_parent.kill()
        check("host ends with its parent", host.exit_code(5) == 0)
        lost = guest2.wait(lambda e: e["event"] == "lost", 5)
        code = guest2.exit_code(5)
        check("guest is told the host closed", lost.get("code") == "closed" and code == 3, f"{lost.get('code')}, exit {code}")

        # --stdin-control, as the mod runs it: "stop" ends it, and so does a closed stdin.
        for name, finish in (("stop on stdin", lambda p: (p.stdin.write(b"stop\n"), p.stdin.flush())), ("closed stdin", lambda p: p.stdin.close())):
            ctl = subprocess.Popen([exe, "host", "--loopback", str(free_port()), "--stdin-control"], stdin=subprocess.PIPE,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=subprocess.CREATE_NO_WINDOW)
            time.sleep(0.5)
            finish(ctl)
            try:
                code = ctl.wait(5)
            except subprocess.TimeoutExpired:
                ctl.kill()
                code = None
            check(f"{name} ends it", code == 0, f"exit {code}")

        # Usage errors and a parent that's already gone.
        bad = subprocess.run([exe, "host", "--minecraft-port", "70000"], capture_output=True, creationflags=subprocess.CREATE_NO_WINDOW)
        check("bad arguments: exit 1", bad.returncode == 1)
        gone = subprocess.run([exe, "host", "--loopback", str(free_port()), "--parent-pid", "4000000"], capture_output=True,
                              creationflags=subprocess.CREATE_NO_WINDOW)
        check("missing parent: exit 2", gone.returncode == 2 and b"parent_gone" in gone.stdout)
    except AssertionError as e:
        check("smoke script", False, str(e))
    finally:
        for link in links:
            link.kill()
        for parent in parents:
            if parent.poll() is None:
                parent.kill()
        with open(os.path.join(report_dir, "link-smoke.json"), "w", encoding="utf-8") as f:
            json.dump({"checks": checks}, f, ensure_ascii=False, indent=1)
        with open(os.path.join(report_dir, "link-smoke.log"), "w", encoding="utf-8") as f:
            f.write("\n".join(log))
    failed = [c for c in checks if not c["ok"]]
    print(f"{len(checks) - len(failed)}/{len(checks)} checks passed", flush=True)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
