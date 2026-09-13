#!/usr/bin/env python3
"""Synthetic tests for the Steam view installer."""

from __future__ import annotations

import json
import os
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

        partial_root = root / "partial-recover"
        partial = make_layout(partial_root)
        install(partial, first)
        partial.backup_root.mkdir()
        retained_partial = partial.backup_root / "interrupted-partial"
        shutil.copytree(partial.plugin, retained_partial)
        (partial.plugin / "extension" / "G-Earth-Facts.jar").unlink()
        partial.pending_upgrade.write_text(
            json.dumps({"schema": 1, "backup": retained_partial.name}), encoding="utf-8"
        )
        install(partial, second)
        assert (partial.plugin / "extension/G-Earth-Facts.jar").read_bytes() == b"version two"
        assert not partial.pending_upgrade.exists()

        legacy_root = root / "legacy-version"
        legacy = make_layout(legacy_root)
        install(legacy, first)
        legacy_name = "G-Earth-Facts-0.0.9"
        legacy_plugin = legacy.profile_extensions / legacy_name
        os.replace(legacy.plugin, legacy_plugin)
        legacy.receipt.write_text(
            json.dumps({"schema": 1, "plugin": legacy_name}), encoding="utf-8"
        )
        install(legacy, second)
        assert legacy.plugin == legacy_plugin
        assert (legacy_plugin / "extension/G-Earth-Facts.jar").read_bytes() == b"version two"
        assert not (legacy.profile_extensions / PLUGIN_DIR).exists()

        relative_root = root / "relative"
        relative = make_layout(relative_root)
        relative_layout = Layout(
            Path(os.path.relpath(relative.profile)),
            Path(os.path.relpath(relative.shared_extensions)),
            Path(os.path.relpath(relative.shared_app)),
        )
        install(relative_layout, first)
        assert (relative.plugin / "extension/G-Earth-Facts.jar").read_bytes() == b"version one"
        assert (relative.profile_extensions / "SharedOne").resolve() == (
            relative.shared_extensions / "SharedOne"
        ).resolve()

        rollback_root = root / "rollback-recovery"
        rollback_layout = make_layout(rollback_root)
        install(rollback_layout, first)
        install(rollback_layout, second)
        rollback_target = next(
            path for path in rollback_layout.backup_root.iterdir() if path.is_dir()
        )
        interrupted_current = rollback_layout.backup_root / "interrupted-current"
        os.replace(rollback_layout.plugin, interrupted_current)
        rollback_layout.pending_rollback.write_text(
            json.dumps(
                {
                    "schema": 1,
                    "operation": "rollback",
                    "target": rollback_target.name,
                    "current": interrupted_current.name,
                }
            ),
            encoding="utf-8",
        )
        rollback(rollback_layout)
        assert (rollback_layout.plugin / "extension/G-Earth-Facts.jar").read_bytes() == b"version one"

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

        overlap_root = root / "overlap"
        overlap = make_layout(overlap_root)
        overlapping = Layout(
            overlap.shared_app,
            overlap.shared_extensions,
            overlap.shared_app,
        )
        try:
            install(overlapping, first)
        except InstallError:
            pass
        else:
            raise AssertionError("installer accepted a Steam profile overlapping shared G-Earth")
        assert not (overlap.shared_app / "Extensions" / PLUGIN_DIR).exists()

        rollback_overlap_root = root / "rollback-overlap"
        rollback_overlap = make_layout(rollback_overlap_root)
        install(rollback_overlap, first)
        install(rollback_overlap, second)
        overlapping_rollback = Layout(
            rollback_overlap.shared_app,
            rollback_overlap.shared_extensions,
            rollback_overlap.shared_app,
        )
        try:
            rollback(overlapping_rollback)
        except InstallError:
            pass
        else:
            raise AssertionError("rollback accepted a Steam profile overlapping shared G-Earth")
        assert not (rollback_overlap.shared_app / "Extensions" / PLUGIN_DIR).exists()

        backup_link_root = root / "backup-link"
        backup_link = make_layout(backup_link_root)
        install(backup_link, first)
        backup_target = backup_link_root / "backup-target"
        backup_target.mkdir()
        backup_link.backup_root.symlink_to(backup_target, target_is_directory=True)
        try:
            install(backup_link, second)
        except InstallError:
            pass
        else:
            raise AssertionError("installer accepted a symlinked backup directory")
        assert (backup_link.plugin / "extension/G-Earth-Facts.jar").read_bytes() == b"version one"

        duplicate = root / "duplicate.zip"
        with zipfile.ZipFile(duplicate, "w") as archive:
            archive.writestr(PLUGIN_DIR + "/command.txt", json.dumps(COMMAND))
            archive.writestr(PLUGIN_DIR + "/./command.txt", json.dumps(COMMAND))
            archive.writestr(PLUGIN_DIR + "/extension/G-Earth-Facts.jar", b"jar")
            archive.writestr(PLUGIN_DIR + "/README.md", "synthetic package")
            archive.writestr(PLUGIN_DIR + "/LICENSE", "MIT")
            archive.writestr(PLUGIN_DIR + "/THIRD-PARTY-NOTICES.md", "notices")
        try:
            validate_package(duplicate)
        except InstallError:
            pass
        else:
            raise AssertionError("installer accepted normalized duplicate ZIP members")

        directory_member = root / "directory-member.zip"
        with zipfile.ZipFile(directory_member, "w") as archive:
            archive.writestr(PLUGIN_DIR + "/command.txt", json.dumps(COMMAND))
            archive.writestr(PLUGIN_DIR + "/extension/G-Earth-Facts.jar/", "")
            archive.writestr(PLUGIN_DIR + "/README.md", "synthetic package")
            archive.writestr(PLUGIN_DIR + "/LICENSE", "MIT")
            archive.writestr(PLUGIN_DIR + "/THIRD-PARTY-NOTICES.md", "notices")
        try:
            validate_package(directory_member)
        except InstallError:
            pass
        else:
            raise AssertionError("installer accepted a directory in place of the extension JAR")

        empty_jar = root / "empty-jar.zip"
        with zipfile.ZipFile(empty_jar, "w") as archive:
            archive.writestr(PLUGIN_DIR + "/command.txt", json.dumps(COMMAND))
            archive.writestr(PLUGIN_DIR + "/extension/G-Earth-Facts.jar", b"")
            archive.writestr(PLUGIN_DIR + "/README.md", "synthetic package")
            archive.writestr(PLUGIN_DIR + "/LICENSE", "MIT")
            archive.writestr(PLUGIN_DIR + "/THIRD-PARTY-NOTICES.md", "notices")
        try:
            validate_package(empty_jar)
        except InstallError:
            pass
        else:
            raise AssertionError("installer accepted an empty extension JAR")

        bad_command = root / "bad-command.zip"
        with zipfile.ZipFile(bad_command, "w") as archive:
            broken = COMMAND[:-2]
            archive.writestr(PLUGIN_DIR + "/command.txt", json.dumps(broken))
            archive.writestr(PLUGIN_DIR + "/extension/G-Earth-Facts.jar", b"jar")
            archive.writestr(PLUGIN_DIR + "/README.md", "synthetic package")
            archive.writestr(PLUGIN_DIR + "/LICENSE", "MIT")
            archive.writestr(PLUGIN_DIR + "/THIRD-PARTY-NOTICES.md", "notices")
        try:
            validate_package(bad_command)
        except InstallError:
            pass
        else:
            raise AssertionError("installer accepted a command without host placeholders")

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
