#!/usr/bin/env python3
"""Produce the extension and ZIP from a committed source identity."""
import sys

if __name__ == "__main__" and not (sys.flags.isolated and sys.dont_write_bytecode):
    native_os = __import__("posix" if "posix" in sys.builtin_module_names else "nt")
    native_os.execv(sys.executable, [sys.executable, "-I", "-B", __file__, *sys.argv[1:]])

import os
from pathlib import Path
import subprocess

from java21 import environment as java21_environment

ROOT = Path(__file__).resolve().parent.parent


def git(*args: str) -> str:
    return subprocess.check_output(
        ["git", "--no-replace-objects", "--no-optional-locks", "-c", "core.fsmonitor=false", *args],
        cwd=ROOT,
        text=True,
    ).strip()


def main() -> None:
    if git("status", "--porcelain", "--untracked-files=all"):
        raise SystemExit("Commit the intended source before a delivery build")
    revision = git("rev-parse", "HEAD")
    wrapper = ROOT / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    subprocess.run(
        [str(wrapper), "-B", "--no-transfer-progress", "-Dgearth.api.revision=" + revision, "clean", "verify"],
        cwd=ROOT,
        env=java21_environment(),
        check=True,
    )
    if git("status", "--porcelain", "--untracked-files=all"):
        raise SystemExit("Build changed tracked source")
    print("Verified delivery build: " + revision)


if __name__ == "__main__":
    main()
