#!/usr/bin/env python3
"""A root-only, same-server backup of only Stint. No off-site protection."""
import datetime
import gzip
import os
from pathlib import Path
import shutil
import subprocess
import time


def main():
    if os.geteuid() != 0:
        raise SystemExit("Run through the root-owned stint-backup.service.")
    os.umask(0o077)
    folder = Path("/var/backups/stint")
    folder.mkdir(parents=True, mode=0o700, exist_ok=True)
    folder.chmod(0o700)
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    temporary = folder / f".stint-{stamp}.sql.gz.tmp"
    final = folder / f"stint-{stamp}.sql.gz"
    command = ["mysqldump", "--no-defaults", "--protocol=socket", "--user=root",
               "--single-transaction", "--quick", "--no-tablespaces",
               "--set-gtid-purged=OFF", "--databases", "stint"]
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        with gzip.open(temporary, "wb", compresslevel=6) as output:
            shutil.copyfileobj(process.stdout, output, length=64 * 1024)
        process.stdout.close()
        error = process.stderr.read().decode("utf-8", errors="replace")
        if process.wait() != 0:
            raise RuntimeError("Stint database dump failed: " + error[:400])
        temporary.chmod(0o600)
        temporary.replace(final)
    except BaseException:
        process.kill()
        process.wait()
        temporary.unlink(missing_ok=True)
        raise
    cutoff = time.time() - 7 * 24 * 3600
    for old in folder.glob("stint-*.sql.gz"):
        if old.is_file() and not old.is_symlink() and old.stat().st_mtime < cutoff:
            old.unlink()
    print(f"Stint backup complete: {final.name}; same-server retention: 7 days.")


if __name__ == "__main__":
    main()
