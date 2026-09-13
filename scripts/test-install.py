#!/usr/bin/env python3
"""Synthetic tests for the Steam view installer."""

from __future__ import annotations

import json
from pathlib import Path
import shutil
import sys
import tempfile
import zipfile

from install_steam import InstallError, Layout, PLUGIN_DIR, install, rollback, validate_package


COMMAND = [
    r"C:\G-Earth\jre\bin\java.exe",
    r"-Dgearthfacts.stateDir=C:\G-Earth\steam-profile\AppData\Local\G-Earth Facts",
    "-jar",
    "G-Earth-Facts.jar",
    "-p",
    "{port}",
    "-f",
    "{filename}",
    "-c",
    "{cookie}",
]


def package(path: Path, marker: bytes) -> None:
    root = PLUGIN_DIR + "/"
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr(root + "command.txt", json.dumps(COMMAND))
        archive.writestr(root + "extension/G-Earth-Facts.jar", marker)
        archive.writestr(root + "README.md", "synthetic package")
        archive.writestr(root + "LICENSE", "MIT")
        archive.writestr(root + "THIRD-PARTY-NOTICES.md", "notices")


def make_layout(root: Path) -> Layout:
    shared_app = root / "shared" / "G-Earth"
    shared_extensions = shared_app / "Extensions"
    shared_extensions.mkdir(parents=True)
    (shared_extensions / "SharedOne").mkdir()
    (shared_extensions / "SharedTwo").mkdir()
    profile = root / "steam" / "G-Earth" / "steam-profile"
    profile.parent.mkdir(parents=True)
    return Layout(profile, shared_extensions, shared_app)


def main() -> None:
    with tempfile.TemporaryDirectory(prefix="g-earth-facts-installer-") as temporary:
        root = Path(temporary)
        layout = make_layout(root)
        (layout.shared_app / "gearth-nitro-v2.crt").write_text("certificate", encoding="ascii")
        (layout.shared_app / "gearth-nitro-v2.key").write_text("key", encoding="ascii")
        first = root / "first.zip"
        second = root / "second.zip"
        package(first, b"version one")
        package(second, b"version two")

        validate_package(first)
        result = install(layout, first)
        assert layout.plugin.is_dir()
        assert (layout.profile_extensions / "SharedOne").is_symlink()
        assert (layout.profile_extensions / "SharedOne").resolve() == (
            layout.shared_extensions / "SharedOne"
        ).resolve()
        assert (layout.profile / "gearth-nitro-v2.crt").is_symlink()
        assert not (layout.shared_extensions / PLUGIN_DIR).exists()
        assert result["backup"] is None

        invalid = root / "invalid.zip"
        with zipfile.ZipFile(invalid, "w") as archive:
            archive.writestr(PLUGIN_DIR + "/command.txt", "not json")
        try:
            install(layout, invalid)
        except InstallError:
            pass
        else:
            raise AssertionError("installer accepted an invalid replacement package")
        assert (layout.plugin / "extension/G-Earth-Facts.jar").read_bytes() == b"version one"

        install(layout, second)
        assert (layout.plugin / "extension/G-Earth-Facts.jar").read_bytes() == b"version two"
        backup = Path(next(path for path in layout.backup_root.iterdir() if path.is_dir()))
        rollback(layout)
        assert (layout.plugin / "extension/G-Earth-Facts.jar").read_bytes() == b"version one"
        assert backup.exists() or any(layout.backup_root.iterdir())
        assert (layout.profile_extensions / "SharedTwo").is_symlink()

        recover_root = root / "recover"
        recover = make_layout(recover_root)
        install(recover, first)
        recover.backup_root.mkdir()
        retained = recover.backup_root / "interrupted"
        shutil.copytree(recover.plugin, retained)
        shutil.rmtree(recover.plugin)
        recover.pending_upgrade.write_text(
            json.dumps({"schema": 1, "backup": retained.name}), encoding="utf-8"
        )
        install(recover, second)
        assert (recover.plugin / "extension/G-Earth-Facts.jar").read_bytes() == b"version two"
        assert not recover.pending_upgrade.exists()

        unknown_root = root / "unknown"
        unknown = make_layout(unknown_root)
        unknown.profile.mkdir(parents=True)
        unknown.profile_extensions.mkdir()
        (unknown.profile_extensions / "Unmanaged").mkdir()
        try:
            install(unknown, first)
        except InstallError:
            pass
        else:
            raise AssertionError("installer accepted an unrecognized Steam extension entry")
        assert (unknown.profile_extensions / "Unmanaged").is_dir()
        assert not unknown.plugin.exists()

        collision_root = root / "collision"
        collision = make_layout(collision_root)
        conflicting = collision_root / "elsewhere"
        conflicting.mkdir()
        collision.profile.mkdir(parents=True)
        collision.profile_extensions.mkdir()
        (collision.profile_extensions / "SharedOne").symlink_to(conflicting, target_is_directory=True)
        try:
            install(collision, first)
        except InstallError:
            pass
        else:
            raise AssertionError("installer accepted an unfamiliar link collision")
        assert not collision.plugin.exists()

        unsafe = root / "unsafe.zip"
        with zipfile.ZipFile(unsafe, "w") as archive:
            archive.writestr("../escape", "bad")
        try:
            validate_package(unsafe)
        except InstallError:
            pass
        else:
            raise AssertionError("installer accepted a path-traversal ZIP")
    print("Steam installer synthetic tests passed")


if __name__ == "__main__":
    sys.exit(main())
