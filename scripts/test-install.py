#!/usr/bin/env python3
"""Synthetic tests for the Steam view installer."""

from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
from unittest.mock import patch
import zipfile

from install_steam import (
    FIRST_INSTALL_TOKEN,
    InstallError,
    Layout,
    PLUGIN_DIR,
    install,
    rollback,
    validate_package,
)


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

        exposed_root = root / "exposed-upgrade"
        exposed = make_layout(exposed_root)
        install(exposed, first)
        install(exposed, second)
        original_backup = next(path for path in exposed.backup_root.iterdir() if path.is_dir())
        exposed.pending_upgrade.write_text(
            json.dumps({"schema": 1, "backup": original_backup.name}), encoding="utf-8"
        )
        install(exposed, second)
        rollback(exposed)
        assert (exposed.plugin / "extension/G-Earth-Facts.jar").read_bytes() == b"version one"

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

        first_recovery_root = root / "first-install-recovery"
        first_recovery = make_layout(first_recovery_root)
        first_recovery.profile.mkdir(parents=True)
        first_recovery.profile_extensions.mkdir()
        partial_first = first_recovery.profile_extensions / PLUGIN_DIR
        partial_first.mkdir()
        (partial_first / "partial.txt").write_text("incomplete", encoding="utf-8")
        first_token = "a" * 32
        (partial_first / FIRST_INSTALL_TOKEN).write_text(
            first_token + "\n", encoding="ascii"
        )
        first_recovery.pending_upgrade.write_text(
            json.dumps(
                {
                    "schema": 1,
                    "operation": "first-install",
                    "plugin": PLUGIN_DIR,
                    "token": first_token,
                }
            ),
            encoding="utf-8",
        )
        install(first_recovery, second)
        assert (first_recovery.plugin / "extension/G-Earth-Facts.jar").read_bytes() == b"version two"
        assert not first_recovery.pending_upgrade.exists()

        unrelated_first_root = root / "unrelated-first-install"
        unrelated_first = make_layout(unrelated_first_root)
        unrelated_first.profile.mkdir(parents=True)
        unrelated_first.profile_extensions.mkdir()
        unrelated_destination = unrelated_first.profile_extensions / PLUGIN_DIR
        unrelated_destination.mkdir()
        (unrelated_destination / "keep.txt").write_text("preserve", encoding="utf-8")
        unrelated_token = "b" * 32
        unrelated_first.pending_upgrade.write_text(
            json.dumps(
                {
                    "schema": 1,
                    "operation": "first-install",
                    "plugin": PLUGIN_DIR,
                    "token": unrelated_token,
                }
            ),
            encoding="utf-8",
        )
        try:
            install(unrelated_first, second)
        except InstallError:
            pass
        else:
            raise AssertionError("first-install recovery deleted an unrelated directory")
        assert (unrelated_destination / "keep.txt").read_text(encoding="utf-8") == "preserve"
        assert unrelated_first.pending_upgrade.exists()

        first_rollback_recovery_root = root / "first-rollback-recovery"
        first_rollback_recovery = make_layout(first_rollback_recovery_root)
        first_rollback_recovery.profile.mkdir(parents=True)
        first_rollback_recovery.profile_extensions.mkdir()
        first_rollback_plugin = first_rollback_recovery.profile_extensions / PLUGIN_DIR
        first_rollback_plugin.mkdir()
        (first_rollback_plugin / FIRST_INSTALL_TOKEN).write_text(
            "c" * 32 + "\n", encoding="ascii"
        )
        first_rollback_recovery.pending_upgrade.write_text(
            json.dumps(
                {
                    "schema": 1,
                    "operation": "first-install",
                    "plugin": PLUGIN_DIR,
                    "token": "c" * 32,
                }
            ),
            encoding="utf-8",
        )
        try:
            rollback(first_rollback_recovery)
        except InstallError:
            pass
        else:
            raise AssertionError("rollback accepted an incomplete first-install recovery")
        assert not first_rollback_plugin.exists()
        assert not first_rollback_recovery.pending_upgrade.exists()

        restore_collision_root = root / "restore-collision"
        restore_collision = make_layout(restore_collision_root)
        install(restore_collision, first)
        restore_collision.backup_root.mkdir()
        retained_restore = restore_collision.backup_root / "interrupted-restore"
        shutil.copytree(restore_collision.plugin, retained_restore)
        shutil.rmtree(restore_collision.plugin)
        restore_stage = restore_collision.profile_extensions / ".G-Earth-Facts.restore"
        restore_stage.mkdir()
        (restore_stage / "keep.txt").write_text("preserve", encoding="utf-8")
        restore_collision.pending_upgrade.write_text(
            json.dumps({"schema": 1, "backup": retained_restore.name}), encoding="utf-8"
        )
        try:
            install(restore_collision, second)
        except InstallError:
            pass
        else:
            raise AssertionError("installer deleted an occupied restore staging path")
        assert (restore_stage / "keep.txt").read_text(encoding="utf-8") == "preserve"

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

        shared_managed_root = root / "shared-managed-name"
        shared_managed = make_layout(shared_managed_root)
        (shared_managed.shared_extensions / "G-Earth-Facts-0.0.9").mkdir()
        try:
            install(shared_managed, first)
        except InstallError:
            pass
        else:
            raise AssertionError("installer accepted a managed name in the shared profile")
        assert not shared_managed.plugin.exists()

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

        backup_overlap_root = root / "backup-overlap"
        backup_overlap_profile = backup_overlap_root / "steam" / "G-Earth" / "steam-profile"
        backup_overlap_app = backup_overlap_profile.parent / ".g-earth-facts-backups"
        backup_overlap_extensions = backup_overlap_app / "Extensions"
        backup_overlap_extensions.mkdir(parents=True)
        (backup_overlap_extensions / "SharedOne").mkdir()
        backup_overlap = Layout(
            backup_overlap_profile, backup_overlap_extensions, backup_overlap_app
        )
        try:
            install(backup_overlap, first)
        except InstallError:
            pass
        else:
            raise AssertionError("installer accepted a backup root overlapping shared G-Earth")
        assert not backup_overlap.plugin.exists()

        profile_backup_overlap_root = root / "profile-backup-overlap"
        profile_backup_overlap_profile = profile_backup_overlap_root / ".g-earth-facts-backups"
        profile_backup_overlap_shared_app = profile_backup_overlap_root / "shared" / "G-Earth"
        profile_backup_overlap_shared_extensions = profile_backup_overlap_shared_app / "Extensions"
        profile_backup_overlap_shared_extensions.mkdir(parents=True)
        (profile_backup_overlap_shared_extensions / "SharedOne").mkdir()
        profile_backup_overlap = Layout(
            profile_backup_overlap_profile,
            profile_backup_overlap_shared_extensions,
            profile_backup_overlap_shared_app,
        )
        try:
            install(profile_backup_overlap, first)
        except InstallError:
            pass
        else:
            raise AssertionError("installer accepted a profile overlapping its backup root")
        assert not (profile_backup_overlap_shared_extensions / PLUGIN_DIR).exists()

        profile_symlink_root = root / "profile-symlink"
        profile_symlink = make_layout(profile_symlink_root)
        real_profile = profile_symlink_root / "real-profile"
        real_profile.mkdir()
        profile_symlink.profile.symlink_to(real_profile, target_is_directory=True)
        try:
            install(profile_symlink, first)
        except InstallError:
            pass
        else:
            raise AssertionError("installer accepted a symlinked Steam profile")
        assert not (real_profile / "Extensions" / PLUGIN_DIR).exists()

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

        broken_certificate_root = root / "broken-certificate"
        broken_certificate = make_layout(broken_certificate_root)
        (broken_certificate.shared_app / "gearth-nitro-v2.crt").symlink_to(
            broken_certificate_root / "missing.crt"
        )
        try:
            install(broken_certificate, first)
        except InstallError:
            pass
        else:
            raise AssertionError("installer accepted a broken shared certificate link")
        assert not broken_certificate.plugin.exists()

        directory_certificate_root = root / "directory-certificate"
        directory_certificate = make_layout(directory_certificate_root)
        (directory_certificate.shared_app / "gearth-nitro-v2.key").mkdir()
        try:
            install(directory_certificate, first)
        except InstallError:
            pass
        else:
            raise AssertionError("installer accepted a directory certificate path")
        assert not directory_certificate.plugin.exists()

        absent_source_directory_root = root / "absent-source-directory-certificate"
        absent_source_directory = make_layout(absent_source_directory_root)
        absent_source_directory.profile.mkdir(parents=True)
        (absent_source_directory.profile / "gearth-nitro-v2.crt").mkdir()
        try:
            install(absent_source_directory, first)
        except InstallError:
            pass
        else:
            raise AssertionError("installer accepted a directory certificate without a shared source")
        assert (absent_source_directory.profile / "gearth-nitro-v2.crt").is_dir()
        assert not absent_source_directory.plugin.exists()

        absent_source_symlink_root = root / "absent-source-symlink-certificate"
        absent_source_symlink = make_layout(absent_source_symlink_root)
        absent_source_symlink.profile.mkdir(parents=True)
        (absent_source_symlink.profile / "gearth-nitro-v2.key").symlink_to(
            absent_source_symlink_root / "missing.key"
        )
        try:
            install(absent_source_symlink, first)
        except InstallError:
            pass
        else:
            raise AssertionError("installer accepted a symlinked certificate without a shared source")
        assert (absent_source_symlink.profile / "gearth-nitro-v2.key").is_symlink()
        assert not absent_source_symlink.plugin.exists()

        copy_failure_root = root / "copy-failure"
        copy_failure = make_layout(copy_failure_root)
        install(copy_failure, first)
        real_copytree = shutil.copytree

        def partial_copy(source, destination, *args, **kwargs):
            real_copytree(source, destination, *args, **kwargs)
            raise OSError("synthetic copy failure")

        with patch("install_steam.shutil.copytree", side_effect=partial_copy):
            try:
                install(copy_failure, second)
            except InstallError:
                pass
            else:
                raise AssertionError("installer accepted a failed backup copy")
        assert (copy_failure.plugin / "extension/G-Earth-Facts.jar").read_bytes() == b"version one"
        assert not list(copy_failure.backup_root.iterdir())

        backup_name_collision_root = root / "backup-name-collision"
        backup_name_collision = make_layout(backup_name_collision_root)
        install(backup_name_collision, first)
        occupied_backup = backup_name_collision.backup_root / "G-Earth-Facts-0.1.0-20990101T010000.000000Z"
        occupied_backup.mkdir(parents=True)
        (occupied_backup / "keep.txt").write_text("preserve", encoding="utf-8")
        with patch("install_steam._backup_name", return_value=occupied_backup):
            try:
                install(backup_name_collision, second)
            except InstallError:
                pass
            else:
                raise AssertionError("installer overwrote a colliding backup path")
        assert (occupied_backup / "keep.txt").read_text(encoding="utf-8") == "preserve"
        assert (backup_name_collision.plugin / "extension/G-Earth-Facts.jar").read_bytes() == b"version one"

        symlink_plugin_root = root / "symlink-plugin"
        symlink_plugin = make_layout(symlink_plugin_root)
        install(symlink_plugin, first)
        external_command = symlink_plugin_root / "external-command.txt"
        external_command.write_text("external", encoding="utf-8")
        (symlink_plugin.plugin / "command.txt").unlink()
        (symlink_plugin.plugin / "command.txt").symlink_to(external_command)
        try:
            install(symlink_plugin, second)
        except InstallError:
            pass
        else:
            raise AssertionError("installer accepted a symlinked managed command")
        assert (symlink_plugin.plugin / "command.txt").is_symlink()
        assert (symlink_plugin.plugin / "extension/G-Earth-Facts.jar").read_bytes() == b"version one"

        symlink_backup_root = root / "symlink-backup"
        symlink_backup = make_layout(symlink_backup_root)
        install(symlink_backup, first)
        install(symlink_backup, second)
        symlink_backup_target = next(path for path in symlink_backup.backup_root.iterdir() if path.is_dir())
        external_jar = symlink_backup_root / "external.jar"
        external_jar.write_bytes(b"external")
        managed_jar = symlink_backup_target / "extension" / "G-Earth-Facts.jar"
        managed_jar.unlink()
        managed_jar.symlink_to(external_jar)
        try:
            rollback(symlink_backup)
        except InstallError:
            pass
        else:
            raise AssertionError("rollback accepted a symlinked retained JAR")
        assert (symlink_backup.plugin / "extension/G-Earth-Facts.jar").read_bytes() == b"version two"

        missing_receipt_root = root / "missing-receipt"
        missing_receipt = make_layout(missing_receipt_root)
        install(missing_receipt, first)
        missing_receipt.receipt.unlink()
        try:
            install(missing_receipt, second)
        except InstallError:
            pass
        else:
            raise AssertionError("installer adopted a plugin without its receipt")
        assert (missing_receipt.plugin / "extension/G-Earth-Facts.jar").read_bytes() == b"version one"
        try:
            rollback(missing_receipt)
        except InstallError:
            pass
        else:
            raise AssertionError("rollback adopted a plugin without its receipt")

        receipt_collision_root = root / "receipt-collision"
        receipt_collision = make_layout(receipt_collision_root)
        install(receipt_collision, first)
        install(receipt_collision, second)
        receipt_collision.receipt.unlink()
        receipt_collision.receipt.mkdir()
        try:
            rollback(receipt_collision)
        except InstallError:
            pass
        else:
            raise AssertionError("rollback accepted an unfamiliar receipt entry")
        assert (receipt_collision.plugin / "extension/G-Earth-Facts.jar").read_bytes() == b"version two"

        timestamp_root = root / "timestamp-order"
        timestamp = make_layout(timestamp_root)
        install(timestamp, first)
        install(timestamp, second)
        older_backup = timestamp.backup_root / "G-Earth-Facts-0.9.0-20990101T010000.000000Z"
        newer_backup = timestamp.backup_root / "G-Earth-Facts-0.10.0-20990101T020000.000000Z"
        shutil.copytree(timestamp.plugin, older_backup)
        shutil.copytree(timestamp.plugin, newer_backup)
        (older_backup / "extension" / "G-Earth-Facts.jar").write_bytes(b"version 0.9")
        (newer_backup / "extension" / "G-Earth-Facts.jar").write_bytes(b"version 0.10")
        rollback(timestamp)
        assert (timestamp.plugin / "extension/G-Earth-Facts.jar").read_bytes() == b"version 0.10"

        backup_collision_root = root / "backup-collision"
        backup_collision = make_layout(backup_collision_root)
        install(backup_collision, first)
        install(backup_collision, second)
        untrusted_backup = backup_collision.backup_root / "untrusted"
        shutil.copytree(backup_collision.plugin, untrusted_backup)
        try:
            rollback(backup_collision)
        except InstallError:
            pass
        else:
            raise AssertionError("rollback accepted an unfamiliar backup entry")
        assert (backup_collision.plugin / "extension/G-Earth-Facts.jar").read_bytes() == b"version two"

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

        stale_link_root = root / "stale-link"
        stale_link = make_layout(stale_link_root)
        install(stale_link, first)
        shutil.rmtree(stale_link.shared_extensions / "SharedOne")
        install(stale_link, second)
        assert not (stale_link.profile_extensions / "SharedOne").exists()
        assert (stale_link.plugin / "extension/G-Earth-Facts.jar").read_bytes() == b"version two"

        stale_link_collision_root = root / "stale-link-collision"
        stale_link_collision = make_layout(stale_link_collision_root)
        install(stale_link_collision, first)
        shutil.rmtree(stale_link_collision.shared_extensions / "SharedOne")
        stale_destination = stale_link_collision.profile_extensions / "SharedOne"
        stale_destination.unlink()
        stale_destination.symlink_to(
            stale_link_collision_root / "unrelated", target_is_directory=True
        )
        try:
            install(stale_link_collision, second)
        except InstallError:
            pass
        else:
            raise AssertionError("installer replaced an unrelated stale extension link")
        assert stale_destination.is_symlink()
        assert (
            stale_link_collision.plugin / "extension/G-Earth-Facts.jar"
        ).read_bytes() == b"version one"

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
