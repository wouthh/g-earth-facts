"""Locate and select a Java 21 JDK for reproducible project commands."""

from __future__ import annotations

import os
from pathlib import Path
import re
import shutil
import subprocess


def _major(java: Path) -> int | None:
    try:
        result = subprocess.run(
            [str(java), "-version"], capture_output=True, text=True, timeout=5
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    match = re.search(r'version "(\d+)', result.stderr + result.stdout)
    return int(match.group(1)) if match else None


def find() -> Path:
    candidates: list[Path] = []
    configured = os.environ.get("JAVA_HOME")
    if configured:
        candidates.append(Path(configured))
    candidates.extend(Path("/usr/lib/jvm").glob("*"))
    candidates.extend(Path.home().glob(".jdks/*"))
    candidates.extend(
        Path.home().glob("Documents/Coding projects/*/.build/tools/jdk-21*")
    )
    java = shutil.which("java")
    if java:
        candidates.append(Path(java).resolve().parent.parent)
    seen: set[Path] = set()
    for candidate in candidates:
        candidate = candidate.expanduser().resolve()
        if candidate in seen:
            continue
        seen.add(candidate)
        if (candidate / "bin" / "java").is_file() and _major(candidate / "bin" / "java") == 21:
            return candidate
    raise RuntimeError("A Java 21 JDK is required; set JAVA_HOME to an installed Java 21 JDK")


def environment() -> dict[str, str]:
    java_home = find()
    env = dict(os.environ)
    env["JAVA_HOME"] = str(java_home)
    env["PATH"] = str(java_home / "bin") + os.pathsep + env.get("PATH", "")
    return env
