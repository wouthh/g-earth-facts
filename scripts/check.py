#!/usr/bin/env python3
"""Canonical local gate: bootstrap, test, package, ZIP and public-hygiene checks."""
from pathlib import Path
import subprocess
import sys

from install_steam import validate_package
from java21 import environment as java21_environment

ROOT = Path(__file__).resolve().parent.parent
BUILD_ENV = java21_environment()
subprocess.run(
    [sys.executable, str(ROOT / "scripts" / "bootstrap.py")], cwd=ROOT, env=BUILD_ENV, check=True
)
subprocess.run(
    [str(ROOT / "mvnw"), "-B", "--no-transfer-progress", "clean", "verify"],
    cwd=ROOT,
    env=BUILD_ENV,
    check=True,
)
subprocess.run([sys.executable, str(ROOT / "scripts" / "check-public.py")], cwd=ROOT, check=True)
zip_path = ROOT / "target" / "G-Earth-Facts-0.1.0-extension.zip"
if not zip_path.is_file():
    raise SystemExit("Extension ZIP was not produced")
subprocess.run(["unzip", "-t", str(zip_path)], cwd=ROOT, check=True, stdout=subprocess.DEVNULL)
package_info = validate_package(zip_path)
if package_info["members"] < 8:
    raise SystemExit("Extension ZIP is missing expected package files")
subprocess.run([sys.executable, str(ROOT / "scripts" / "test-install.py")], cwd=ROOT, check=True)
subprocess.run(["git", "diff", "--check"], cwd=ROOT, check=True)
print("G-Earth Facts local gate passed")
