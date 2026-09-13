#!/usr/bin/env python3
"""Run the shaded JAR against a synthetic G-Earth extension host."""

from __future__ import annotations

from pathlib import Path
import socket
import struct
import subprocess
import tempfile

from java21 import find as find_java21


ROOT = Path(__file__).resolve().parent.parent
JAR = ROOT / "target" / "g-earth-facts-0.1.0.jar"


def read_exact(connection: socket.socket, length: int) -> bytes:
    result = bytearray()
    while len(result) < length:
        chunk = connection.recv(length - len(result))
        if not chunk:
            raise AssertionError("packaged extension closed its fake-host socket early")
        result.extend(chunk)
    return bytes(result)


def main() -> None:
    if not JAR.is_file():
        raise SystemExit(f"packaged JAR is missing: {JAR}")
    with tempfile.TemporaryDirectory(prefix="g-earth-facts-packaged-") as state:
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen(1)
            listener.settimeout(5)
            process = subprocess.Popen(
                [
                    str(find_java21() / "bin" / "java"),
                    "-Djava.awt.headless=true",
                    f"-Dgearthfacts.stateDir={state}",
                    "-jar",
                    str(JAR),
                    "-p",
                    str(listener.getsockname()[1]),
                    "-f",
                    "synthetic-host",
                    "-c",
                    "synthetic-cookie",
                ],
                cwd=JAR.parent,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=False,
            )
            try:
                connection, _ = listener.accept()
                with connection:
                    connection.settimeout(5)
                    # HPacket(new HPacket(2)) on the public extension wire.
                    connection.sendall(struct.pack(">IH", 2, 2))
                    length = struct.unpack(">I", read_exact(connection, 4))[0]
                    body = read_exact(connection, length)
                    if len(body) < 2 or body[:2] != b"\x00\x01":
                        raise AssertionError("packaged extension did not return ExtensionInfo")
                    if b"G-Earth Facts" not in body:
                        raise AssertionError("packaged extension info did not contain its title")
                    connection.shutdown(socket.SHUT_WR)
                return_code = process.wait(timeout=5)
                if return_code != 0:
                    stderr = process.stderr.read().decode("utf-8", "replace")
                    raise AssertionError(f"packaged extension exited {return_code}: {stderr}")
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait(timeout=5)
                if process.stdout:
                    process.stdout.close()
                if process.stderr:
                    process.stderr.close()
    print("Packaged fake-host smoke test passed")


if __name__ == "__main__":
    main()
