#!/usr/bin/env python3
"""Build the pinned public G-Earth API into the private Maven cache."""
import sys

if __name__ == "__main__" and not (sys.flags.isolated and sys.dont_write_bytecode):
    native_os = __import__("posix" if "posix" in sys.builtin_module_names else "nt")
    native_os.execv(sys.executable, [sys.executable, "-I", "-B", __file__, *sys.argv[1:]])

sys.path.insert(0, str(__import__("pathlib").Path(__file__).resolve().parent))

import hashlib
import io
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import urllib.request

from java21 import environment as java21_environment

ROOT = Path(__file__).resolve().parent.parent
PIN = "b993d5ba0b23ab5644633abb8074229d1cb53b71"
ARCHIVE_SHA256 = "3a5fa509635420b8554d32ced03a33c3d4a07be2a4f497b05eb6598ae9fc39fa"
CACHE = ROOT / ".build"


def main() -> None:
    CACHE.mkdir(exist_ok=True)
    archive = CACHE / "gearth-source.tar.gz"
    if not archive.exists():
        archive.write_bytes(
            urllib.request.urlopen(
                "https://codeload.github.com/G-Realm/G-Earth/tar.gz/" + PIN,
                timeout=60,
            ).read()
        )
    if hashlib.sha256(archive.read_bytes()).hexdigest() != ARCHIVE_SHA256:
        raise SystemExit("Pinned G-Earth API archive checksum mismatch")
    with tempfile.TemporaryDirectory(prefix="api-", dir=CACHE) as work:
        with tarfile.open(fileobj=io.BytesIO(archive.read_bytes())) as source:
            source.extractall(work, filter="data")
        api = Path(work) / ("G-Earth-" + PIN) / "G-Earth-Api"
        extension = api / "src/main/java/gearth/extensions/Extension.java"
        text = extension.read_text()
        old_loop = """                int amountRead = 0;

                while (amountRead < length) {
                    amountRead += dIn.read(headerandbody, 4 + amountRead, Math.min(dIn.available(), length - amountRead));
                }"""
        if text.count(old_loop) != 1:
            raise SystemExit("Pinned API frame-loop precondition failed")
        text = text.replace(old_loop, "                dIn.readFully(headerandbody, 4, length);")
        old_buffer = "                byte[] headerandbody = new byte[length + 4];"
        if text.count(old_buffer) != 1:
            raise SystemExit("Pinned API frame-bound precondition failed")
        text = text.replace(
            old_buffer,
            '                if (length < 2 || length > 4_000_000) throw new IOException("Invalid host frame length");\n'
            + old_buffer,
        )
        extension.write_text(text)
        env = java21_environment()
        env["MAVEN_USER_HOME"] = str(CACHE / "maven-user-home")
        wrapper = ROOT / ("mvnw.cmd" if os.name == "nt" else "mvnw")
        subprocess.run(
            [
                str(wrapper),
                "-B",
                "-q",
                "-f",
                str(ROOT / "build-support/api-pom.xml"),
                "-Dmaven.repo.local=" + str(CACHE / "m2"),
                "-Dapi.source=" + str(api),
                "clean",
                "install",
            ],
            cwd=ROOT,
            env=env,
            check=True,
        )
    print("Pinned G-Earth API built: " + PIN)


if __name__ == "__main__":
    main()
