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
    names: set[str] = set()
    total = 0
    try:
        with zipfile.ZipFile(zip_path) as archive:
            for info in archive.infolist():
                name, _ = _safe_member(info)
                if name in names:
                    raise InstallError(f"Duplicate ZIP member: {name}")
                names.add(name)
                total += max(0, info.file_size)
                if total > MAX_PACKAGE_BYTES:
                    raise InstallError("Extension ZIP is too large")
            required = {
                f"{PLUGIN_DIR}/command.txt",
                f"{PLUGIN_DIR}/extension/G-Earth-Facts.jar",
                f"{PLUGIN_DIR}/README.md",
                f"{PLUGIN_DIR}/LICENSE",
                f"{PLUGIN_DIR}/THIRD-PARTY-NOTICES.md",
            }
            missing = required - names
            if missing:
                raise InstallError("Extension ZIP is missing: " + ", ".join(sorted(missing)))
            command = json.loads(archive.read(f"{PLUGIN_DIR}/command.txt").decode("utf-8"))
    except (KeyError, OSError, zipfile.BadZipFile, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise InstallError("Extension ZIP is not a valid folder package") from exc
    valid_command = (
        isinstance(command, list)
        and bool(command)
        and all(isinstance(value, str) for value in command)
        and command[0] == r"C:\G-Earth\jre\bin\java.exe"
        and r"-Dgearthfacts.stateDir=C:\G-Earth\steam-profile\AppData\Local\G-Earth Facts"
        in command
    )
    if valid_command:
        jar_index = command.index("-jar") if "-jar" in command else -1
        valid_command = jar_index >= 0 and jar_index + 1 < len(command)
        valid_command = valid_command and command[jar_index + 1] == "G-Earth-Facts.jar"
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


def _preflight(layout: Layout) -> tuple[list[Path], list[tuple[Path, Path]], Path | None]:
    _require_directory(layout.shared_app, "shared G-Earth directory")
    _require_directory(layout.shared_extensions, "shared Extensions directory")
    if not layout.profile.parent.is_dir() or layout.profile.parent.is_symlink():
        raise InstallError(f"Steam profile parent is unavailable: {layout.profile.parent}")
    _require_directory(layout.profile, "Steam profile", allow_missing=True)
    _require_directory(layout.profile_extensions, "Steam Extensions directory", allow_missing=True)

    shared_children = sorted(layout.shared_extensions.iterdir(), key=lambda path: path.name)
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


def install(layout: Layout, zip_path: Path) -> dict[str, object]:
    """Install one package, preserving existing entries and an upgrade backup."""
    validate_package(zip_path)
    shared_children, cert_links, old_plugin = _preflight(layout)
    layout.profile.mkdir(parents=True, exist_ok=True)
    layout.profile_extensions.mkdir(parents=True, exist_ok=True)
    stage_parent = Path(tempfile.mkdtemp(prefix=".g-earth-facts-stage-", dir=layout.profile.parent))
    backup: Path | None = None
    created_links: list[Path] = []
    created_certs: list[Path] = []
    previous_receipt = layout.receipt.read_bytes() if layout.receipt.is_file() else None
    try:
        stage_root = _extract_package(zip_path, stage_parent)
        if old_plugin is not None:
            layout.backup_root.mkdir(mode=0o700, exist_ok=True)
            backup = _backup_name(layout.backup_root)
            shutil.copytree(old_plugin, backup, symlinks=True)
            shutil.rmtree(old_plugin)
        os.replace(stage_root, layout.plugin)
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
    except Exception as exc:
        for path in [*created_links, *created_certs]:
            if _lexists(path):
                path.unlink()
        if _lexists(layout.plugin):
            shutil.rmtree(layout.plugin)
        if backup is not None and _lexists(backup) and not _lexists(layout.plugin):
            shutil.move(backup, layout.plugin)
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
    _require_directory(layout.profile_extensions, "Steam Extensions directory")
    if not _known_plugin(layout.plugin):
        raise InstallError(f"Current Steam plugin is missing or unfamiliar: {layout.plugin}")
    if not layout.backup_root.is_dir() or layout.backup_root.is_symlink():
        raise InstallError("No retained G-Earth Facts backup is available")
    backups = sorted(
        (path for path in layout.backup_root.iterdir() if _known_plugin(path)),
        key=lambda path: path.name,
    )
    if not backups:
        raise InstallError("No retained G-Earth Facts backup is available")
    current = _backup_name(layout.backup_root)
    shutil.move(layout.plugin, current)
    shutil.move(backups[-1], layout.plugin)
    _atomic_json(
        layout.receipt,
        {"schema": 1, "plugin": PLUGIN_DIR, "rollback": backups[-1].name},
    )
    return {"restored": str(layout.plugin), "previous": str(current)}


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
