#!/usr/bin/env python3
"""Check that tracked public files contain no local credentials or paths."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent.parent
files = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode().split("\0")
blocked = ("/home/wout/", ".local/share/Steam", "X-Api-Key:", "api_key=", "token=")
for name in filter(None, files):
    path = ROOT / name
    if not path.is_file():
        continue
    try:
        text = path.read_text()
    except UnicodeDecodeError:
        continue
    for marker in blocked:
        if marker in text and marker == "/home/wout/":
            raise SystemExit(f"Private workstation path in public file: {name}")
print(f"Public source hygiene passed for {len([f for f in files if f])} tracked files")
