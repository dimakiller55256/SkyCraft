"""Integration check of the Windows report collector using synthetic local files."""
import csv
import io
import json
from pathlib import Path
import socket
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    fixture_root = ROOT / ".tools" / "report-tests"
    fixture_root.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="fixture-", dir=fixture_root) as temporary:
        base = Path(temporary)
        assert base.resolve().is_relative_to(ROOT), "Fixture cleanup must stay within the repository"
        game = base / "game"
        for directory in ("mods", "config", "logs/skycraft-network"):
            (game / directory).mkdir(parents=True, exist_ok=True)
        secret = "SYNTHETIC_PASSWORD_DO_NOT_EXPORT_927"
        (game / "config/skycraft.properties").write_text(
            "join=example.invalid:25565\nnetwork.mode=SOCKS5\nnetwork.proxy=127.0.0.1:1080\n"
            f"network.proxy.username={secret}\nnetwork.proxy.password={secret}\n"
            "network.hostPort=25565\nnetwork.timeoutMillis=10000\n", encoding="utf-8")
        (game / "accounts.json").write_text(secret, encoding="utf-8")
        (game / "logs/latest.log").write_text(secret, encoding="utf-8")
        (game / "mods/skycraft-fixture.jar").write_bytes(b"synthetic-not-a-real-mod")
        events = [
            dict(schema=1, utc="2026-10-03T12:00:00Z", seq=1, run_id="SELFTEST", role="client", event="marker", marker="=1+1"),
            dict(schema=1, utc="2026-10-03T12:00:01Z", seq=2, run_id="SELFTEST", role="client", event="sample", ping_ms=20),
            dict(schema=1, utc="2026-10-03T12:00:02Z", seq=3, run_id="SELFTEST", role="client", event="sample", ping_ms=40),
            dict(schema=1, utc="2026-10-03T12:00:03Z", run_id="SELFTEST", role="client", event="trace_closed", dropped_events=0),
        ]
        (game / "logs/skycraft-network/SELFTEST-client-fixture.jsonl").write_text(
            "\n".join(json.dumps(event) for event in events) + "\n", encoding="utf-8")
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            fixture_port = listener.getsockname()[1]
            listener.listen()
            with socket.create_connection(listener.getsockname()) as connection:
                peer, _ = listener.accept()
                with peer:
                    for run_id, target in (("SELFTEST", "127.0.0.1"), ("MISSING", "localhost")):
                        command = ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                                   str(ROOT / "tools/collect-network-report.ps1"), "-GameDirectory", str(game),
                                   "-RunId", run_id, "-Role", "Client", "-TargetHost", target,
                                   "-TargetPort", str(listener.getsockname()[1]), "-OutputDirectory", str(base / "reports"),
                                   "-Result", "partial", "-NetworkLabel", "Loopback", "-Notes", "Synthetic collector fixture"]
                        process = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=90)
                        if process.returncode:
                            raise RuntimeError(process.stdout.decode("utf-8", errors="replace"))
        archives = list((base / "reports").glob("*.zip"))
        assert len(archives) == 2
        for archive in archives:
            with zipfile.ZipFile(archive) as package:
                names = package.namelist()
                assert not any("accounts" in name or "properties" in name or "latest.log" in name for name in names)
                for name in names:
                    assert secret.encode() not in package.read(name), name
                run = json.loads(package.read("run.json").decode("utf-8-sig"))
                summary = json.loads(package.read("summary.json").decode("utf-8-sig"))
                assert run["Target"]["Host"] in ("localhost", "127.0.0.1")
                assert run["Target"]["Port"] == fixture_port
                assert summary["Result"] == "partial"
                assert summary["CollectionErrors"] == 0, package.read("collection-errors.json")
                settings = json.loads(package.read("skycraft-settings.json").decode("utf-8-sig"))
                assert settings["ProxyCredentialsSet"] is True
                if run["RunId"] == "SELFTEST":
                    assert summary["Events"] == 4 and summary["TraceStopped"] is True
                    assert summary["PingMeanMs"] == 30 and summary["PingMaxMs"] == 40
                    rows = list(csv.DictReader(io.StringIO(package.read("events.csv").decode("utf-8-sig")), delimiter=";"))
                    assert rows[0]["marker"] == "'=1+1"
                    tcp = list(csv.DictReader(io.StringIO(package.read("tcp-samples.csv").decode("utf-8-sig")), delimiter=";"))
                    assert any(row["state"] == "Established" for row in tcp), summary
                else:
                    assert summary["TraceFiles"] == 0
                    warnings = json.loads(package.read("warnings.json").decode("utf-8-sig"))
                    assert any("Нет лога" in warning for warning in warnings)
        print("REPORT COLLECTOR PASS: local TCP, IP/DNS targets, ZIP, UTF-8, no secret/config/account export, safe CSV, missing-trace warning")


if __name__ == "__main__":
    main()
