#!/usr/bin/env python3
"""Private local MySQL for Stint; never changes the existing MySQL service."""
import argparse
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
LOCAL = ROOT / ".local"
MYSQL_ROOT = LOCAL / "mysql"
DATA = MYSQL_ROOT / "data"
SOCKET = MYSQL_ROOT / "mysql.sock"
PID = MYSQL_ROOT / "mysql.pid"
CONFIG = LOCAL / "dev.json"


def binaries():
    client = shutil.which("mysql")
    if not client:
        raise SystemExit("MySQL is required. Install MySQL before running this script.")
    server = str(Path(client).resolve().parent / "mysqld")
    if not Path(server).exists():
        server = shutil.which("mysqld")
    if not server:
        raise SystemExit("Could not find mysqld next to mysql.")
    return client, server


def configuration():
    LOCAL.mkdir(mode=0o700, exist_ok=True)
    LOCAL.chmod(0o700)
    if CONFIG.exists():
        return json.loads(CONFIG.read_text())
    cfg = {
        "STINT_HOST": "127.0.0.1",
        "STINT_PORT": "8787",
        "STINT_DB_URL": "jdbc:mysql://127.0.0.1:3307/stint_dev?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
        "STINT_DB_USER": "stint",
        "STINT_DB_PASSWORD": secrets.token_hex(24),
        "STINT_AUTH_TOKEN": secrets.token_urlsafe(32),
        "STINT_SERVER_URL": "http://127.0.0.1:8787",
        "STINT_ALLOW_INSECURE_LOCALHOST": "true",
        "STINT_TEST_DB_URL": "jdbc:mysql://127.0.0.1:3307/stint_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
        "STINT_TEST_DB_USER": "stint",
    }
    cfg["STINT_TEST_DB_PASSWORD"] = cfg["STINT_DB_PASSWORD"]
    fd = os.open(CONFIG, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as stream:
        json.dump(cfg, stream, indent=2)
    return cfg


def mysql_query(client, sql, check=True):
    return subprocess.run(
        [client, "--no-defaults", "--protocol=socket", f"--socket={SOCKET}",
         "--user=root", "--batch", "--skip-column-names"],
        input=sql, text=True, capture_output=True, check=check,
    )


def start():
    client, server = binaries()
    cfg = configuration()
    MYSQL_ROOT.mkdir(mode=0o700, exist_ok=True)
    MYSQL_ROOT.chmod(0o700)
    if not (DATA / "mysql").is_dir():
        DATA.mkdir(mode=0o700, exist_ok=True)
        init = subprocess.run(
            [server, "--no-defaults", "--initialize-insecure", f"--datadir={DATA}"],
            capture_output=True, text=True,
        )
        if init.returncode:
            raise SystemExit("MySQL initialization failed:\n" + init.stderr[-2500:])
    probe = mysql_query(client, "SELECT 1;", check=False)
    if probe.returncode:
        log = open(MYSQL_ROOT / "mysqld.log", "ab", buffering=0)
        subprocess.Popen(
            [server, "--no-defaults", f"--datadir={DATA}", "--port=3307",
             "--bind-address=127.0.0.1", f"--socket={SOCKET}", f"--pid-file={PID}",
             "--mysqlx=0", "--skip-name-resolve", "--innodb-buffer-pool-size=67108864",
             "--max-connections=30", "--performance-schema=OFF"],
            stdin=subprocess.DEVNULL, stdout=log, stderr=log, start_new_session=True,
        )
        log.close()
        for _ in range(150):
            if mysql_query(client, "SELECT 1;", check=False).returncode == 0:
                break
            time.sleep(0.2)
        else:
            raise SystemExit(f"MySQL did not become ready; inspect {MYSQL_ROOT / 'mysqld.log'}")
    # All names and credentials below are created by this script, never user SQL.
    password = cfg["STINT_DB_PASSWORD"]
    mysql_query(client, f"""
CREATE DATABASE IF NOT EXISTS stint_dev CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS stint_test CHARACTER SET utf8mb4;
CREATE USER IF NOT EXISTS 'stint'@'127.0.0.1' IDENTIFIED BY '{password}';
GRANT ALL PRIVILEGES ON stint_dev.* TO 'stint'@'127.0.0.1';
GRANT ALL PRIVILEGES ON stint_test.* TO 'stint'@'127.0.0.1';
""")
    print("Private MySQL ready on 127.0.0.1:3307 (stint_dev / stint_test).")
    print(f"Local credentials: {CONFIG} (private, ignored by Git).")


def stop():
    client, _ = binaries()
    if not SOCKET.exists():
        print("Private MySQL is not running.")
        return
    mysql_query(client, "SHUTDOWN;")
    print("Stopped only Stint's private MySQL instance.")


def run_gradle(task, extra):
    cfg = configuration()
    if task == ":composeApp:run":
        cfg["STINT_ALLOW_INSECURE_LOCALHOST"] = "true"
    env = os.environ | cfg
    wrapper = ROOT / "gradlew"
    if not wrapper.exists():
        raise SystemExit("Gradle wrapper is not ready yet.")
    return subprocess.call([str(wrapper), task, *extra], cwd=ROOT, env=env)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["start", "stop", "status", "server", "desktop", "test"])
    args, extra = parser.parse_known_args()
    if args.action == "start":
        start()
    elif args.action == "stop":
        stop()
    elif args.action == "status":
        client, _ = binaries()
        result = mysql_query(client, "SELECT VERSION();", check=False)
        print("Private MySQL: " + (result.stdout.strip() if result.returncode == 0 else "stopped"))
    else:
        task = {"server": ":server:run", "desktop": ":composeApp:run", "test": ":server:test"}[args.action]
        raise SystemExit(run_gradle(task, extra))


if __name__ == "__main__":
    main()
