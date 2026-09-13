#!/usr/bin/env python3
"""Install or roll back the extension in a dedicated Steam G-Earth view.

The canonical Bottles extension directory is read-only input to this script.
Only the dedicated Steam view receives managed links.  The Steam launcher takes
the shared launch lock before starting G-Earth; installation can therefore be
performed while that launcher is running without changing its active view.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import datetime as dt
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import tempfile
import zipfile


VERSION = "0.1.0"
PLUGIN_DIR = f"G-Earth-Facts-{VERSION}"
ZIP_NAME = f"G-Earth-Facts-{VERSION}-extension.zip"
MAX_PACKAGE_BYTES = 64 * 1024 * 1024
CERT_NAMES = ("gearth-nitro-v2.crt", "gearth-nitro-v2.key")


class InstallError(RuntimeError):
    """A refusal that leaves the existing Steam view untouched."""


@dataclass(frozen=True)
class Layout:
    profile: Path
    shared_extensions: Path
    shared_app: Path

    @property
    def profile_extensions(self) -> Path:
        return self.profile / "Extensions"

    @property
    def plugin(self) -> Path:
        return self.profile_extensions / PLUGIN_DIR

    @property
    def backup_root(self) -> Path:
        return self.profile.parent / ".g-earth-facts-backups"

    @property
    def receipt(self) -> Path:
        return self.profile / ".g-earth-facts-install.json"

    @property
    def pending_upgrade(self) -> Path:
        return self.profile.parent / f".{PLUGIN_DIR}.upgrade.json"

    @property
    def pending_rollback(self) -> Path:
        return self.profile.parent / f".{PLUGIN_DIR}.rollback.json"


def default_layout() -> Layout:
    home = Path.home()
    compat = Path(
        os.environ.get("GEARTH_STEAM_COMPATDATA", home / ".local/share/g-earth-steam/compatdata")
    )
    shared_app = Path(
        os.environ.get(
            "GEARTH_SHARED_APP",
            home / ".var/app/com.usebottles.bottles/data/bottles/bottles/habbo-gearth/drive_c/G-Earth/geproton9",
        )
    )
    return Layout(
        profile=compat / "pfx/drive_c/G-Earth/steam-profile",
        shared_extensions=shared_app / "Extensions",
        shared_app=shared_app,
    )


def _lexists(path: Path) -> bool:
    return os.path.lexists(os.fspath(path))


def _require_directory(path: Path, label: str, allow_missing: bool = False) -> None:
    if not _lexists(path):
        if allow_missing:
            return
        raise InstallError(f"{label} is missing: {path}")
    if path.is_symlink() or not path.is_dir():
        raise InstallError(f"{label} must be a real directory: {path}")


def _same_target(link: Path, source: Path) -> bool:
    if not link.is_symlink():
        return False
    try:
        return link.resolve(strict=True) == source.resolve(strict=True)
    except OSError:
        return False


def _safe_member(info: zipfile.ZipInfo) -> tuple[str, tuple[str, ...]]:
    name = info.filename
    if not name or "\\" in name:
        raise InstallError(f"Unsafe ZIP member: {name!r}")
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts:
        raise InstallError(f"Unsafe ZIP member: {name!r}")
    parts = path.parts
    if not parts or parts[0] != PLUGIN_DIR:
        raise InstallError(f"Unexpected ZIP root: {name!r}")
    mode = (info.external_attr >> 16) & 0xFFFF
    if stat.S_ISLNK(mode):
        raise InstallError(f"ZIP symlinks are not permitted: {name!r}")
    return name, parts


def validate_package(zip_path: Path) -> dict[str, object]:
    """Validate the folder-extension shape and command without extracting it."""
    if not zip_path.is_file():
        raise InstallError(f"Extension ZIP is missing: {zip_path}")
    names: set[tuple[str, ...]] = set()
    total = 0
    try:
        with zipfile.ZipFile(zip_path) as archive:
            for info in archive.infolist():
                name, parts = _safe_member(info)
                if parts in names:
                    raise InstallError(f"Duplicate ZIP member: {name}")
                names.add(parts)
                total += max(0, info.file_size)
                if total > MAX_PACKAGE_BYTES:
                    raise InstallError("Extension ZIP is too large")
            required = {
                tuple(PurePosixPath(path).parts)
                for path in (
                    f"{PLUGIN_DIR}/command.txt",
                    f"{PLUGIN_DIR}/extension/G-Earth-Facts.jar",
                    f"{PLUGIN_DIR}/README.md",
                    f"{PLUGIN_DIR}/LICENSE",
                    f"{PLUGIN_DIR}/THIRD-PARTY-NOTICES.md",
                )
            }
            missing = required - names
            if missing:
                missing_text = ", ".join("/".join(parts) for parts in sorted(missing))
                raise InstallError("Extension ZIP is missing: " + missing_text)
            command = json.loads(archive.read(f"{PLUGIN_DIR}/command.txt").decode("utf-8"))
    except (KeyError, OSError, zipfile.BadZipFile, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise InstallError("Extension ZIP is not a valid folder package") from exc
    valid_command = command == [
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
    if not valid_command:
        raise InstallError("Extension command.txt does not select the Steam Java 21 profile")
    return {"members": len(names), "bytes": total, "command": command}


def _extract_package(zip_path: Path, destination: Path) -> Path:
    validate_package(zip_path)
    stage_root = destination / PLUGIN_DIR
    with zipfile.ZipFile(zip_path) as archive:
        for info in archive.infolist():
            _, parts = _safe_member(info)
            target = stage_root.joinpath(*parts[1:])
            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(info) as source, target.open("wb") as output:
                shutil.copyfileobj(source, output, length=1024 * 1024)
    return stage_root


def _known_plugin(path: Path) -> bool:
    return (
        path.is_dir()
        and not path.is_symlink()
        and (path / "command.txt").is_file()
        and (path / "extension" / "G-Earth-Facts.jar").is_file()
    )


def _remove_owned_plugin(path: Path) -> None:
    if not _lexists(path):
        return
    if path.is_symlink() or not path.is_dir():
        raise InstallError(f"Steam plugin path is not an owned directory: {path}")
    shutil.rmtree(path)


def _restore_plugin_backup(layout: Layout, backup: Path) -> None:
    if not _known_plugin(backup):
        raise InstallError(f"Steam plugin backup is unavailable: {backup}")
    _remove_owned_plugin(layout.plugin)
    restore = layout.profile_extensions / f".{PLUGIN_DIR}.restore"
    _remove_owned_plugin(restore)
    shutil.copytree(backup, restore, symlinks=True)
    os.replace(restore, layout.plugin)


def _read_pending_upgrade(layout: Layout) -> Path | None:
    """Recover an interrupted replacement before inspecting the Steam view."""
    pending = layout.pending_upgrade
    if not _lexists(pending):
        return None
    if pending.is_symlink() or not pending.is_file():
        raise InstallError(f"Pending upgrade marker is not a regular file: {pending}")
    try:
        state = json.loads(pending.read_text(encoding="utf-8"))
        backup_name = state["backup"]
    except (OSError, UnicodeDecodeError, json.JSONDecodeError, KeyError, TypeError):
        raise InstallError(f"Pending upgrade marker is invalid: {pending}")
    if (
        not isinstance(backup_name, str)
        or not backup_name
        or Path(backup_name).name != backup_name
        or Path(backup_name).is_absolute()
    ):
        raise InstallError(f"Pending upgrade marker has an unsafe backup: {pending}")
    backup = layout.backup_root / backup_name
    if not _known_plugin(backup):
        raise InstallError(f"Pending upgrade backup is unavailable: {backup}")
    if _lexists(layout.plugin):
        if not _known_plugin(layout.plugin):
            raise InstallError(f"Steam plugin is unfamiliar while recovering: {layout.plugin}")
        # The replacement reached its destination before the marker was cleared.
        pending.unlink()
        return backup

    if not layout.profile_extensions.is_dir() or layout.profile_extensions.is_symlink():
        raise InstallError(f"Steam Extensions directory is unavailable: {layout.profile_extensions}")
    _restore_plugin_backup(layout, backup)
    pending.unlink()
    return backup


def _read_pending_rollback(layout: Layout) -> dict[str, str | None] | None:
    """Finish an interrupted rollback before selecting another backup."""
    pending = layout.pending_rollback
    if not _lexists(pending):
        return None
    if pending.is_symlink() or not pending.is_file():
        raise InstallError(f"Pending rollback marker is not a regular file: {pending}")
    try:
        state = json.loads(pending.read_text(encoding="utf-8"))
        if state.get("schema") != 1 or state.get("operation") != "rollback":
            raise ValueError
        target_name = state["target"]
        current_name = state.get("current")
    except (
        OSError,
        UnicodeDecodeError,
        json.JSONDecodeError,
        AttributeError,
        KeyError,
        TypeError,
        ValueError,
    ):
        raise InstallError(f"Pending rollback marker is invalid: {pending}")

    def backup_path(name: object, label: str) -> Path | None:
        if name is None:
            return None
        if (
            not isinstance(name, str)
            or not name
            or Path(name).name != name
            or Path(name).is_absolute()
        ):
            raise InstallError(f"Pending rollback marker has an unsafe {label}: {pending}")
        return layout.backup_root / name

    target = backup_path(target_name, "target")
    current = backup_path(current_name, "current")
    if target is None:
        raise InstallError(f"Pending rollback marker has no target: {pending}")
    if _lexists(layout.plugin):
        if not _known_plugin(layout.plugin):
            raise InstallError(f"Steam plugin is unfamiliar while recovering: {layout.plugin}")
        completed = not _known_plugin(target) and current is not None and _known_plugin(current)
        pending.unlink()
        if completed:
            return {
                "target": target.name,
                "current": current.name if current is not None else None,
            }
        return None
    if not layout.profile_extensions.is_dir() or layout.profile_extensions.is_symlink():
        raise InstallError(f"Steam Extensions directory is unavailable: {layout.profile_extensions}")
    source = target if _known_plugin(target) else current
    if source is None or not _known_plugin(source):
        raise InstallError(f"Pending rollback backups are unavailable: {pending}")
    os.replace(source, layout.plugin)
    pending.unlink()
    return {
        "target": target.name,
        "current": current.name if current is not None else None,
    }


def _preflight(layout: Layout) -> tuple[list[Path], list[tuple[Path, Path]], Path | None]:
    _require_directory(layout.shared_app, "shared G-Earth directory")
    _require_directory(layout.shared_extensions, "shared Extensions directory")
    if not layout.profile.parent.is_dir() or layout.profile.parent.is_symlink():
        raise InstallError(f"Steam profile parent is unavailable: {layout.profile.parent}")
    _require_directory(layout.profile, "Steam profile", allow_missing=True)
    _require_directory(layout.profile_extensions, "Steam Extensions directory", allow_missing=True)

    shared_children = sorted(layout.shared_extensions.iterdir(), key=lambda path: path.name)
    expected_names = {PLUGIN_DIR, *(child.name for child in shared_children)}
    if layout.profile_extensions.is_dir():
        for child in layout.profile_extensions.iterdir():
            if child.name not in expected_names:
                raise InstallError(f"Unrecognized Steam extension entry: {child}")
    for child in shared_children:
        if child.name == PLUGIN_DIR:
            raise InstallError(f"The shared profile already owns {PLUGIN_DIR}")
        if not _lexists(child):
            raise InstallError(f"Shared extension disappeared during preflight: {child}")
        destination = layout.profile_extensions / child.name
        if _lexists(destination) and not _same_target(destination, child):
            raise InstallError(f"Steam extension-link collision: {destination}")

    old_plugin: Path | None = None
    if _lexists(layout.plugin):
        if not _known_plugin(layout.plugin):
            raise InstallError(f"Steam plugin name is occupied by an unfamiliar entry: {layout.plugin}")
        old_plugin = layout.plugin

    cert_links: list[tuple[Path, Path]] = []
    for name in CERT_NAMES:
        source = layout.shared_app / name
        destination = layout.profile / name
        if _lexists(source):
            if not source.is_file() and not source.is_symlink():
                raise InstallError(f"Shared certificate path is not a file: {source}")
            if _lexists(destination) and not _same_target(destination, source):
                raise InstallError(f"Steam certificate-path collision: {destination}")
            if not _lexists(destination):
                cert_links.append((destination, source))
    if _lexists(layout.receipt) and (
        layout.receipt.is_symlink() or not layout.receipt.is_file()
    ):
        raise InstallError(f"Steam installation receipt is not a regular file: {layout.receipt}")
    return shared_children, cert_links, old_plugin


def _atomic_json(path: Path, value: dict[str, object]) -> None:
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=".install-", suffix=".tmp", dir=path.parent
    )
    os.close(descriptor)
    temporary = Path(temporary_name)
    try:
        temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def _backup_name(root: Path) -> Path:
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    return root / f"{PLUGIN_DIR}-{stamp}"


def _absolute_layout(layout: Layout) -> Layout:
    return Layout(
        layout.profile.expanduser().resolve(),
        layout.shared_extensions.expanduser().resolve(),
        layout.shared_app.expanduser().resolve(),
    )


def install(layout: Layout, zip_path: Path) -> dict[str, object]:
    """Install one package, preserving existing entries and an upgrade backup."""
    layout = _absolute_layout(layout)
    validate_package(zip_path)
    _read_pending_rollback(layout)
    _read_pending_upgrade(layout)
    shared_children, cert_links, old_plugin = _preflight(layout)
    layout.profile.mkdir(parents=True, exist_ok=True)
    layout.profile_extensions.mkdir(parents=True, exist_ok=True)
    stage_parent = Path(tempfile.mkdtemp(prefix=".g-earth-facts-stage-", dir=layout.profile.parent))
    backup: Path | None = None
    plugin_replaced = False
    created_links: list[Path] = []
    created_certs: list[Path] = []
    previous_receipt = layout.receipt.read_bytes() if layout.receipt.is_file() else None
    pending_written = False
    try:
        stage_root = _extract_package(zip_path, stage_parent)
        if old_plugin is not None:
            layout.backup_root.mkdir(mode=0o700, exist_ok=True)
            backup = _backup_name(layout.backup_root)
            shutil.copytree(old_plugin, backup, symlinks=True)
            pending_written = True
            _atomic_json(layout.pending_upgrade, {"schema": 1, "backup": backup.name})
            shutil.rmtree(old_plugin)
        os.replace(stage_root, layout.plugin)
        plugin_replaced = True
        for child in shared_children:
            destination = layout.profile_extensions / child.name
            if not _lexists(destination):
                if not _lexists(child):
                    raise InstallError(f"Shared extension disappeared during install: {child}")
                os.symlink(child, destination, target_is_directory=child.is_dir())
                created_links.append(destination)
        for destination, source in cert_links:
            os.symlink(source, destination)
            created_certs.append(destination)
        _atomic_json(
            layout.receipt,
            {
                "schema": 1,
                "plugin": PLUGIN_DIR,
                "managedLinks": [child.name for child in shared_children],
                "certificates": [destination.name for destination, _ in cert_links],
            },
        )
        if pending_written:
            layout.pending_upgrade.unlink(missing_ok=True)
    except Exception as exc:
        for path in [*created_links, *created_certs]:
            if _lexists(path):
                path.unlink()
        if pending_written and backup is not None:
            try:
                _restore_plugin_backup(layout, backup)
            except Exception as recovery:
                raise InstallError(
                    "Steam installation failed; pending recovery marker was retained"
                ) from recovery
            layout.pending_upgrade.unlink(missing_ok=True)
        elif plugin_replaced and _lexists(layout.plugin):
            _remove_owned_plugin(layout.plugin)
        if previous_receipt is None:
            layout.receipt.unlink(missing_ok=True)
        else:
            layout.receipt.write_bytes(previous_receipt)
        if isinstance(exc, InstallError):
            raise
        raise InstallError("Steam installation rolled back after an unexpected failure") from exc
    finally:
        shutil.rmtree(stage_parent, ignore_errors=True)
    return {
        "plugin": str(layout.plugin),
        "managedLinks": [child.name for child in shared_children],
        "backup": str(backup) if backup else None,
        "profile": str(layout.profile),
    }


def rollback(layout: Layout) -> dict[str, object]:
    """Restore the newest retained plugin backup without touching shared links."""
    layout = _absolute_layout(layout)
    recovered = _read_pending_rollback(layout)
    if recovered is not None:
        _atomic_json(
            layout.receipt,
            {"schema": 1, "plugin": PLUGIN_DIR, "rollback": recovered["target"]},
        )
        return {"restored": str(layout.plugin), "previous": recovered["current"]}
    _read_pending_upgrade(layout)
    _require_directory(layout.profile_extensions, "Steam Extensions directory")
    if _lexists(layout.plugin) and not _known_plugin(layout.plugin):
        raise InstallError(f"Current Steam plugin is missing or unfamiliar: {layout.plugin}")
    if not layout.backup_root.is_dir() or layout.backup_root.is_symlink():
        raise InstallError("No retained G-Earth Facts backup is available")
    backups = sorted(
        (path for path in layout.backup_root.iterdir() if _known_plugin(path)),
        key=lambda path: path.name,
    )
    if not backups:
        raise InstallError("No retained G-Earth Facts backup is available")
    current: Path | None = None
    pending_written = False
    target = backups[-1]
    try:
        if _lexists(layout.plugin):
            current = _backup_name(layout.backup_root)
            _atomic_json(
                layout.pending_rollback,
                {
                    "schema": 1,
                    "operation": "rollback",
                    "target": target.name,
                    "current": current.name,
                },
            )
            pending_written = True
            os.replace(layout.plugin, current)
        os.replace(target, layout.plugin)
        _atomic_json(
            layout.receipt,
            {"schema": 1, "plugin": PLUGIN_DIR, "rollback": target.name},
        )
        if pending_written:
            layout.pending_rollback.unlink(missing_ok=True)
    except Exception as exc:
        if pending_written and not _lexists(layout.plugin):
            source = target if _known_plugin(target) else current
            if source is not None and _known_plugin(source):
                os.replace(source, layout.plugin)
                layout.pending_rollback.unlink(missing_ok=True)
        if isinstance(exc, InstallError):
            raise
        raise InstallError("Rollback interrupted; recovery state was retained") from exc
    return {"restored": str(layout.plugin), "previous": str(current) if current else None}


def _parse_args() -> argparse.Namespace:
    defaults = default_layout()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--zip", type=Path, default=Path("target") / ZIP_NAME)
    parser.add_argument("--profile", type=Path, default=defaults.profile)
    parser.add_argument("--shared-extensions", type=Path, default=defaults.shared_extensions)
    parser.add_argument("--shared-app", type=Path, default=defaults.shared_app)
    parser.add_argument("--rollback", action="store_true")
    return parser.parse_args()


def main() -> None:
    args = _parse_args()
    layout = Layout(args.profile, args.shared_extensions, args.shared_app)
    result = rollback(layout) if args.rollback else install(layout, args.zip)
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
