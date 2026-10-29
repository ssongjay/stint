#!/usr/bin/env python3
"""Run read-only Stint acceptance checks on the private foldable emulator."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
SDK = Path("/opt/android-sdk")
ADB = SDK / "platform-tools/adb"
SERIAL = "emulator-5554"
PACKAGE = "dev.stint.app"
RUNNER = "dev.stint.app.test/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS = "dev.stint.app.FoldableAcceptanceTest"
APK = ROOT / "composeApp/build/outputs/apk/debug/composeApp-debug.apk"
TEST_APK = ROOT / "composeApp/build/outputs/apk/androidTest/debug/composeApp-debug-androidTest.apk"
PRIVATE_CONFIG = ROOT / ".local/production.json"
OUTPUT = ROOT / "artifacts/screenshots/android"


def run(arguments, *, input_bytes=None, timeout=120, capture=True):
    result = subprocess.run(
        [str(ADB), "-s", SERIAL, *arguments],
        input=input_bytes,
        capture_output=capture,
        timeout=timeout,
    )
    if result.returncode:
        # Commands contain no credentials. Never include stdin or environment in errors.
        detail = result.stderr.decode(errors="replace")[-1200:] if capture else ""
        raise RuntimeError(f"adb command failed ({result.returncode}): {detail}")
    return result.stdout if capture else b""


def wait_for_boot():
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        if run(["shell", "getprop", "sys.boot_completed"], timeout=5).strip() == b"1":
            return
        time.sleep(1)
    raise RuntimeError("The foldable emulator did not finish booting")


def install():
    for artifact in (APK, TEST_APK):
        if not artifact.is_file():
            raise RuntimeError(f"Missing test artifact: {artifact}")
        run(["install", "-r", "-t", str(artifact)], timeout=180)


def provision_connection():
    config = json.loads(PRIVATE_CONFIG.read_text())
    payload = json.dumps({"url": config["STINT_SERVER_URL"], "token": config["STINT_AUTH_TOKEN"]}).encode()
    run(["shell", "run-as", PACKAGE, "mkdir", "-p", "files"])
    run(
        ["shell", "run-as", PACKAGE, "dd", "of=files/acceptance-config.json"],
        input_bytes=payload,
    )
    run(["shell", "run-as", PACKAGE, "chmod", "600", "files/acceptance-config.json"])


def configure_posture(name):
    if name == "folded":
        state, rotation = "0", "0"
    elif name == "book":
        state, rotation = "2", "0"
    elif name == "tabletop":
        state, rotation = "1", "1"
    else:
        raise RuntimeError(f"Unsupported posture: {name}")
    run(["shell", "settings", "put", "system", "accelerometer_rotation", "0"])
    run(["shell", "settings", "put", "system", "user_rotation", rotation])
    run(["shell", "cmd", "device_state", "state", state])
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        current = run(["shell", "cmd", "device_state", "print-state"]).decode().strip()
        if state in current:
            return
        time.sleep(.5)
    raise RuntimeError(f"The emulator did not enter {name} posture")


def instrument(method, **arguments):
    command = ["shell", "am", "instrument", "-w", "-r", "-e", "class", f"{TEST_CLASS}#{method}"]
    for key, value in arguments.items():
        command.extend(["-e", key, value])
    command.append(RUNNER)
    output = run(command, timeout=90).decode(errors="replace")
    if "FAILURES!!!" in output or "INSTRUMENTATION_FAILED" in output or "OK (1 test)" not in output:
        raise RuntimeError("Android instrumentation failed:\n" + output[-2500:])


def retrieve_screenshot(name):
    OUTPUT.mkdir(parents=True, exist_ok=True)
    data = run(["exec-out", "run-as", PACKAGE, "cat", f"files/screenshots/{name}.png"])
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise RuntimeError("The app did not produce a valid PNG screenshot")
    target = OUTPUT / f"{name}.png"
    target.write_bytes(data)
    return target


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["install", "capture", "behavior", "transition", "all"])
    parser.add_argument("--posture", choices=["folded", "book", "tabletop"])
    args = parser.parse_args()
    wait_for_boot()
    if args.action in {"install", "all"}:
        install()
        provision_connection()
    if args.action in {"capture", "all"}:
        postures = [args.posture] if args.posture else ["folded", "book", "tabletop"]
        for posture in postures:
            configure_posture(posture)
            name = f"stint-{posture}"
            instrument(
                "rendersConnectedProductionActivityInThePhysicalFoldPosture",
                expectedLayout=posture,
                screenshotName=name,
            )
            print(retrieve_screenshot(name))
    if args.action in {"behavior", "all"}:
        configure_posture("book")
        instrument("controllerAndConnectedSnapshotSurviveActivityRecreationOnUnfoldedDisplay")
        print("Production Activity and controller survived configuration recreation without starting a timer.")
    if args.action == "transition":
        configure_posture("book")
        instrument("controllerAndConnectedSnapshotSurvivePhysicalFoldTransition", runLiveMigration="true")
        print("Production Activity and controller survived a live physical fold transition without starting a timer.")


if __name__ == "__main__":
    try:
        main()
    except (KeyError, OSError, RuntimeError, subprocess.TimeoutExpired, json.JSONDecodeError) as error:
        raise SystemExit(str(error))
