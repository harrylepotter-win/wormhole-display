#!/usr/bin/env python3
"""Capture a labelled receiver run without installing, restarting, or changing profiles."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import uuid

REPO = Path(__file__).resolve().parent.parent
PACKAGE = "io.github.pgodlews.wormhole"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="ADB serial; required if multiple devices are connected")
    parser.add_argument("--duration", type=int, default=60, help="Seconds, 1–1200 (default: 60)")
    parser.add_argument("--label", required=True, help="Sender, workload, profile, audio state, Wi-Fi placement")
    parser.add_argument("--trace-seconds", type=int, default=0, help="Optional Perfetto trace, up to 30 seconds")
    parser.add_argument("--output", type=Path, help="New output directory (must not already exist)")
    args = parser.parse_args()
    if not 1 <= args.duration <= 1200 or not 0 <= args.trace_seconds <= min(30, args.duration):
        parser.error("duration must be 1–1200; trace-seconds must be 0–min(30, duration)")
    sdk_adb = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk")) / "platform-tools/adb"
    adb = shutil.which("adb") or (str(sdk_adb) if sdk_adb.exists() else None)
    if not adb:
        parser.error("adb unavailable; put it on PATH or set ANDROID_HOME")
    if not args.serial:
        devices = subprocess.check_output([adb, "devices"], text=True)
        serials = [line.split()[0] for line in devices.splitlines()[1:] if line.endswith("\tdevice")]
        if len(serials) != 1:
            parser.error("select one connected device with --serial")
        args.serial = serials[0]
    command = [adb, "-s", args.serial]
    subprocess.run(command + ["get-state"], check=True, capture_output=True, timeout=10)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    output = args.output or REPO / "dist/performance" / (stamp + "-" + uuid.uuid4().hex[:6])
    output.mkdir(parents=True, exist_ok=False)
    failures = []

    def capture(name, *tail, timeout=15):
        try:
            result = subprocess.run(command + list(tail), capture_output=True, timeout=timeout)
            combined = result.stdout + result.stderr
            (output / name).write_bytes(combined)
            if result.returncode or b"Can't find service" in combined or b"not found" in combined:
                failures.append({"file": name, "exit": result.returncode, "error": "command failed or service unavailable"})
            return result.stdout.decode(errors="replace")
        except subprocess.TimeoutExpired:
            (output / name).write_text("Timed out; unavailable for this run.\n")
            failures.append({"file": name, "error": "timeout"})
            return ""

    metadata = {
        "startedUtc": datetime.now(timezone.utc).isoformat(), "serial": args.serial,
        "label": args.label, "requestedSeconds": args.duration, "traceSeconds": args.trace_seconds,
        "sourceCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip(),
        "sourceStatus": subprocess.check_output(["git", "status", "--short"], cwd=REPO, text=True),
        "note": "Receiver release requests are not physical presentation or end-to-end latency.",
    }
    apk = REPO / "dist/wormhole-display.apk"
    if apk.exists():
        metadata["localApkSha256"] = hashlib.sha256(apk.read_bytes()).hexdigest()
    capture("device.txt", "shell", "getprop")
    capture("display.txt", "shell", "wm", "size")
    capture("package.txt", "shell", "dumpsys", "package", PACKAGE)
    capture("wifi.txt", "shell", "dumpsys", "wifi")
    package_paths = capture("apk-path.txt", "shell", "pm", "path", PACKAGE)
    # Paths are produced by PackageManager, but validate before adb's remote shell parses them.
    for i, line in enumerate(package_paths.splitlines()):
        path = line.removeprefix("package:").strip()
        if path.startswith("/") and all(c.isalnum() or c in "/._-=+" for c in path):
            capture(f"installed-apk-sha256-{i}.txt", "shell", "sha256sum", path)

    def snapshot(label):
        capture(f"{label}-cpu.txt", "shell", "dumpsys", "cpuinfo")
        capture(f"{label}-memory.txt", "shell", "dumpsys", "meminfo", PACKAGE)
        capture(f"{label}-thermal.txt", "shell", "dumpsys", "thermalservice")
        capture(f"{label}-battery.txt", "shell", "dumpsys", "battery")

    log_file = (output / "logcat.txt").open("wb")
    logcat = subprocess.Popen(command + ["logcat", "-v", "epoch", "-T", "1", "Wormhole:I",
                                       "AndroidRuntime:E", "libc:F", "art:I", "*:S"],
                              stdout=log_file, stderr=subprocess.STDOUT)
    trace = None
    trace_log = None
    trace_file = None
    start = time.monotonic()
    try:
        snapshot("start")
        if args.trace_seconds:
            config = '''buffers { size_kb: 16384 fill_policy: RING_BUFFER }
data_sources { config { name: "linux.ftrace" ftrace_config {
  ftrace_events: "sched/sched_switch"
  ftrace_events: "sched/sched_wakeup"
  ftrace_events: "power/cpu_frequency"
  atrace_categories: "gfx"
  atrace_categories: "view"
  atrace_categories: "video"
  atrace_apps: "io.github.pgodlews.wormhole"
} } }
''' + f"duration_ms: {args.trace_seconds * 1000}\n"
            (output / "trace-config.pbtxt").write_text(config)
            trace_log = (output / "perfetto-status.txt").open("wb")
            trace_file = (output / "trace.pftrace").open("wb")
            # ADB shell v2 with no PTY keeps binary stdout separate from diagnostics.
            # Streaming avoids firmware restrictions on trace output directories.
            trace = subprocess.Popen(command + ["shell", "-T", "perfetto", "--txt", "-c", "-", "-o", "-"],
                                     stdin=subprocess.PIPE, stdout=trace_file, stderr=trace_log)
            trace.stdin.write(config.encode())
            trace.stdin.close()
        sample = 0
        while time.monotonic() - start < args.duration:
            time.sleep(min(5, max(0, args.duration - (time.monotonic() - start))))
            sample += 1
            if sample % 6 == 0 and time.monotonic() - start < args.duration:
                snapshot(f"sample-{sample:04d}")
        snapshot("end")
        if trace is not None:
            trace.wait(timeout=15)
            if trace.returncode:
                failures.append({"file": "perfetto-status.txt", "exit": trace.returncode})

    except (KeyboardInterrupt, subprocess.TimeoutExpired, OSError) as error:
        failures.append({"error": type(error).__name__})
    finally:
        if logcat.poll() is not None:
            failures.append({"file": "logcat.txt", "exit": logcat.returncode, "error": "logcat stopped before capture ended"})
        logcat.terminate()
        try:
            logcat.wait(timeout=5)
        except subprocess.TimeoutExpired:
            logcat.kill()
            logcat.wait()
        log_file.close()
        if trace is not None:
            if trace.poll() is None:
                trace.kill()
                trace.wait()
            trace_log.close()
            trace_file.close()
            if (output / "trace.pftrace").stat().st_size == 0:
                failures.append({"file": "trace.pftrace", "error": "empty trace"})
        metadata["captureSeconds"] = round(time.monotonic() - start, 3)
        metadata["failures"] = failures
        (output / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")

    rows = []
    for line in (output / "logcat.txt").read_text(errors="replace").splitlines():
        if "video_perf " in line:
            prefix, fields = line.split("video_perf ", 1)
            row = dict(item.split("=", 1) for item in fields.split() if "=" in item)
            header = prefix.split()
            if len(header) >= 3:
                row.update(deviceEpoch=header[0], pid=header[1], tid=header[2])
            rows.append(row)
    (output / "video-samples.json").write_text(json.dumps(rows, indent=2) + "\n")
    print(f"{output}\n{len(rows)} video diagnostic samples; {len(failures)} unavailable/failed captures.")
    if not rows:
        print("No instrumented video samples: this is not a streaming performance baseline.")


if __name__ == "__main__":
    main()
