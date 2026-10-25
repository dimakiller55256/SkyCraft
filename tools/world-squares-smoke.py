"""Two real SkyCraft Minecraft clients, each driven by its own Skyrim stand-in, in different Skyrim worlds.

Checks the map squares every Skyrim world gets (SkyWorlds): the host stands in Tamriel (the map's
middle), the guest in an interior (the server's next square). Each Minecraft must put its player in
its own world's square and hand Skyrim back that world's own coordinates. Then the guest's Skyrim walks
out into Tamriel next to the host: the guest's Minecraft must follow into Tamriel's square, and each
Skyrim must hear of the other player (world entity kind 7, what hides Skyrim Together's stand-in).

    python tools/world-squares-smoke.py <report-dir>

Uses only isolated folders .tools/squares-host and .tools/squares-guest and its own shared-memory
names; never the installed Skyrim, Prism instances or saves. Run through tools/dev.ps1 -Action
SquaresSmoke, which exports the two client launches first.
"""
import json
import math
import os
import secrets
import shutil
import socket
import struct
import subprocess
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(Path(__file__).resolve().parent))
import fake_skyrim as fs  # noqa: E402

SLOT = 16384           # SkyWorlds.SLOT_BLOCKS
TAMRIEL = 0x3C
INTERIOR = 0x000165A3  # Breezehome, as Skyrim.esm numbers it
HOST_POS = (fs.X0 + 0.5, 100.0, 0.5)
GUEST_INSIDE = (10.5, 50.0, 10.5)
GUEST_OUTSIDE = (fs.X0 + 3.5, 100.0, 0.5)
TIMEOUT = 300


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def tris_payload(rx, ry, rz, epoch, floor_y):
    tris = []
    if ry * 8 <= floor_y - 1 <= ry * 8 + 7:
        x0, z0, x1, z1 = rx * 8.0, rz * 8.0, rx * 8.0 + 8.0, rz * 8.0 + 8.0
        y = float(floor_y)
        tris.append(struct.pack("<9fI", x0, y, z0, x1, y, z0, x1, y, z1, 8))
        tris.append(struct.pack("<9fI", x0, y, z0, x1, y, z1, x0, y, z1, 8))
    head = struct.pack("<iiiiiiII", rx * 8, ry * 8, rz * 8, rx * 8 + 7, ry * 8 + 7, rz * 8 + 7, epoch, len(tris))
    return head + b"".join(tris)


def send_floor(link, pos, epoch, radius=3):
    """A new collision epoch: a flat floor under pos, in the stand-in's own world coordinates."""
    floor_y = int(pos[1])
    link.write_col(1, struct.pack("<I", epoch))
    rx0, rz0, ry0 = math.floor(pos[0]) // 8, math.floor(pos[2]) // 8, (floor_y - 1) // 8
    for rx in range(rx0 - radius, rx0 + radius + 1):
        for rz in range(rz0 - radius, rz0 + radius + 1):
            for ry in range(ry0 - 1, ry0 + 2):
                link.write_col(3, tris_payload(rx, ry, rz, epoch, floor_y))
                link.write_col(2, fs.region_payload(rx, ry, rz, epoch, lambda x, y, z: fs.FULL if y == floor_y - 1 else None))


class Stand:
    """One Skyrim stand-in: its mapping, world, position, epoch and teleport sequence."""

    def __init__(self, name, world, pos, epoch, tseq):
        self.link = fs.Link(name=name, world=world, plugin=b"Skyrim.esm", local_id=world & 0xFFFFFF)
        self.pos, self.epoch, self.tseq = pos, epoch, tseq
        self.sent = False

    def move(self, world, pos):
        self.link.set_world(world, b"Skyrim.esm", world & 0xFFFFFF)
        self.pos, self.epoch, self.tseq, self.sent = pos, self.epoch + 1, self.tseq + 1, False

    def frame(self):
        self.link.heartbeat()
        if self.link.mc_alive() and not self.sent:
            send_floor(self.link, self.pos, self.epoch)
            self.sent = True
        self.link.write_sky(1, self.pos, -90.0, 10.0, self.tseq, self.epoch)

    def published_here(self):
        """Minecraft drives this stand-in from its own world's coordinates, at the stand-in's spot."""
        mc = self.link.read_mc()
        return mc["flags"] & 1 and mc["ack"] == self.tseq and math.dist(mc["pos"], self.pos) < 0.6, mc

    def other_players(self):
        count, _ = struct.unpack_from("<II", self.link.m, fs.OFF_ENTITIES + 4)
        found = []
        for i in range(min(count, 160)):
            kind, _, x, y, z = struct.unpack_from("<IIfff", self.link.m, fs.OFF_ENTITIES + 0x40 + i * 96)
            if kind == 7:
                found.append((round(x, 2), round(y, 2), round(z, 2)))
        return found


class Client:
    def __init__(self, role, env_extra):
        data = json.loads((ROOT / ".tools" / f"runSquares{role}Client.json").read_text(encoding="utf-8"))
        self.game = Path(data["workingDirectory"])
        assert self.game.resolve() == (ROOT / ".tools" / f"squares-{role.lower()}").resolve()
        self.data, self.role, self.env_extra = data, role, env_extra
        self.session = secrets.token_hex(16)
        self.proc = None
        self.log = None

    def prepare(self, join=None):
        shutil.rmtree(self.game / "saves", ignore_errors=True)
        shutil.rmtree(self.game / "config" / "skycraft-test", ignore_errors=True)
        (self.game / "config" / "skycraft-test").mkdir(parents=True, exist_ok=True)
        (self.game / "options.txt").write_text(
            "lang:ru_ru\nguiScale:2\nonboardAccessibility:false\nsoundCategory_master:0.0\nrenderDistance:3\n"
            "simulationDistance:5\nmaxFps:30\npauseOnLostFocus:false\n", encoding="utf-8")
        props = f"join={join or ''}\nnetwork.mode=DIRECT\n"
        (self.game / "config" / "skycraft.properties").write_text(props, encoding="utf-8")
        self.lease()

    def lease(self):
        expires = (datetime.now(timezone.utc) + timedelta(seconds=20)).strftime("%Y-%m-%dT%H:%M:%SZ")
        path = self.game / "config" / "skycraft-test" / "lease.json"
        temp = path.with_suffix(".tmp")
        temp.write_text(json.dumps({"schema": 1, "session": self.session, "expiresUtc": expires}), encoding="utf-8")
        os.replace(temp, path)

    def start(self, audit):
        env = {k: v for k, v in os.environ.items()}
        temporary = ROOT / ".tools" / "tmp"
        temporary.mkdir(exist_ok=True)
        env["TEMP"] = env["TMP"] = str(temporary)
        env.update(self.env_extra)
        self.log = (audit / f"{self.role}-game.log").open("w", encoding="utf-8")
        self.proc = subprocess.Popen([self.data["java"], *self.data["args"]], cwd=self.game, env=env, stdout=self.log,
                                     stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)

    def state(self):
        try:
            return json.loads((self.game / "config" / "skycraft-test" / "state.json").read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return {}

    def stop(self):
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=20)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        if self.log:
            self.log.close()


def near(state, pos, square):
    p = state.get("position")
    if not p or not state.get("positionReady"):
        return False
    return state.get("worldSquare") == square and math.dist((p["x"], p["y"], p["z"]), pos) < 0.6


def main():
    audit = Path(sys.argv[1])
    audit.mkdir(parents=True, exist_ok=True)
    port = free_port()
    host_stand = Stand(r"Local\SkyCraft_squares_host", TAMRIEL, HOST_POS, 501, 7001)
    guest_stand = Stand(r"Local\SkyCraft_squares_guest", INTERIOR, GUEST_INSIDE, 801, 9001)
    host = Client("Host", {"SKYCRAFT_LAN_PORT": str(port), "SKYCRAFT_LAN_OFFLINE": "1"})
    guest = Client("Guest", {})
    host.prepare()
    guest.prepare(join=f"127.0.0.1:{port}")
    steps, result = [], "FAIL"
    guest_square = f"{INTERIOR:x}@{SLOT},0"
    started = time.monotonic()
    phase, last_lease, last_note = 0, 0.0, 0.0
    try:
        host.start(audit)
        while time.monotonic() - started < TIMEOUT:
            host_stand.frame()
            guest_stand.frame()
            now = time.monotonic()
            if now - last_lease > 5:
                host.lease()
                guest.lease()
                last_lease = now
            hs, gs = host.state(), guest.state()
            if phase == 0 and hs.get("published"):
                steps.append(f"host LAN open on {hs.get('hostPort')} after {now - started:.0f} s")
                guest.start(audit)
                phase = 1
            elif phase == 1:
                host_ok, host_mc = host_stand.published_here()
                guest_ok, guest_mc = guest_stand.published_here()
                if near(hs, HOST_POS, "3c@0,0") and host_ok and near(gs, (SLOT + GUEST_INSIDE[0], GUEST_INSIDE[1], GUEST_INSIDE[2]), guest_square) and guest_ok:
                    steps.append(f"host in Tamriel's square at {hs['position']}; guest in the interior's square {gs['worldSquare']} at {gs['position']}; "
                                 f"both Skyrims driven from their own coordinates ({host_mc['pos']}, {guest_mc['pos']})")
                    if host_stand.other_players() or guest_stand.other_players():
                        raise AssertionError(f"players in different worlds reported to each other: {host_stand.other_players()} {guest_stand.other_players()}")
                    guest_stand.move(TAMRIEL, GUEST_OUTSIDE)
                    phase, phase_at = 2, now
            elif phase == 2:
                guest_ok, guest_mc = guest_stand.published_here()
                if near(gs, GUEST_OUTSIDE, "3c@0,0") and guest_ok:
                    steps.append(f"guest followed its Skyrim into Tamriel's square at {gs['position']}")
                    phase, phase_at = 3, now
            elif phase == 3:
                seen_by_guest, seen_by_host = guest_stand.other_players(), host_stand.other_players()
                if any(math.dist(p, HOST_POS) < 1.0 for p in seen_by_guest) and any(math.dist(p, GUEST_OUTSIDE) < 1.0 for p in seen_by_host):
                    steps.append(f"each Skyrim hears of the other player where they stand: guest sees {seen_by_guest}, host sees {seen_by_host}")
                    result = "PASS"
                    break
            for client in (host, guest):
                if client.proc and client.proc.poll() is not None:
                    raise AssertionError(f"{client.role} Minecraft exited early (code {client.proc.returncode})")
            if now - last_note > 15:
                last_note = now
                print(f"t={now - started:4.0f}s phase {phase}: host {hs.get('worldSquare')} {hs.get('position')} ready={hs.get('positionReady')}; "
                      f"guest {gs.get('worldSquare')} {gs.get('position')} ready={gs.get('positionReady')}", flush=True)
            time.sleep(1 / 60)
        else:
            steps.append(f"timeout in phase {phase}")
    except Exception as error:  # report every failure, never hang
        steps.append(f"error: {error}")
    finally:
        host.stop()
        guest.stop()
        report = {"result": result, "steps": steps, "hostState": host.state(), "guestState": guest.state()}
        (audit / "world-squares.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    for step in steps:
        print(" -", step)
    print(f"{result}: Skyrim worlds in separate Minecraft squares, two real clients", flush=True)
    return 0 if result == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
