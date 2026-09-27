#!/usr/bin/env python3
"""One systemd service supervising independent Terminal Plus guest capabilities."""
import os
from pathlib import Path
import pwd
import signal
import subprocess
import sys
import time

MODULES = ("console-resize", "capture", "proxy")


def proxy_user():
    for name in ("droid", "nobody"):
        try:
            return pwd.getpwnam(name)
        except KeyError:
            pass
    raise RuntimeError("No unprivileged user for port proxy")


def launch(module):
    options = {}
    if module == "proxy":
        user = proxy_user()
        options = dict(user=user.pw_uid, group=user.pw_gid, extra_groups=[])
    script = Path(__file__).with_name("terminal-plus-" + module + ".py")
    return subprocess.Popen([sys.executable, "-u", str(script)], **options)


def main():
    stopping = False
    def stop(*_):
        nonlocal stopping
        stopping = True
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    children = {}
    retry = {}
    try:
        while not stopping:
            for module in MODULES:
                process = children.get(module)
                if process is not None and process.poll() is None:
                    continue
                if process is not None:
                    print(f"{module} exited with {process.returncode}; retrying in 2s", flush=True)
                    del children[module]
                    retry[module] = time.monotonic() + 2
                if time.monotonic() < retry.get(module, 0):
                    continue
                try:
                    children[module] = launch(module)
                    print(f"{module} started", flush=True)
                except (OSError, RuntimeError) as error:
                    print(f"{module} unavailable: {error}", flush=True)
                    retry[module] = time.monotonic() + 10
            time.sleep(1)
    finally:
        for process in children.values():
            if process.poll() is None:
                process.terminate()
        for process in children.values():
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


if __name__ == "__main__":
    main()
