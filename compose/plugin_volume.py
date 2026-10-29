"""Network-free, standard-library plugin-volume worker. Invoked inside an owned helper."""

import argparse
import json
from pathlib import Path
import shutil
import stat
import tarfile
import zipfile

from plugin_preflight import canonical_path, file_hash, require, verify_bundle


def inventory(root):
    """Reject links/special files instead of following anything outside this mount."""
    files, directories = {}, {}
    for path in sorted(root.rglob("*")):
        name = path.relative_to(root).as_posix()
        canonical_path(name)
        mode = path.lstat().st_mode
        if stat.S_ISDIR(mode):
            directories[name] = stat.S_IMODE(mode)
        else:
            require(stat.S_ISREG(mode), f"Nonregular installed plugin entry: {name}")
            files[name] = {"sha256": file_hash(path), "mode": stat.S_IMODE(mode)}
    return {"files": files, "directories": directories}


def write_json(path, value):
    path.write_text(json.dumps(value, sort_keys=True, indent=2) + "\n")


def prepare(work):
    artifact = verify_bundle(work / "plugin.zip", work / "plugin.identity.json")
    candidate = work / "candidate"
    candidate.mkdir()
    with zipfile.ZipFile(work / "plugin.zip") as archive:
        for name in artifact["files"]:
            destination = candidate / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(name) as source, destination.open("xb") as target:
                shutil.copyfileobj(source, target)
            destination.chmod(0o644)
    verify_installed(candidate, artifact)
    write_json(work / "prepared.json", {"build_id": artifact["build_id"], "files": artifact["files"]})
    return artifact


def verify_installed(root, artifact):
    actual = inventory(root)
    require({k: v["sha256"] for k, v in actual["files"].items()} == artifact["files"],
            "Installed plugin file set/checksums differ from selected bundle")
    require(set(actual["directories"]) == {"lib"}, "Unexpected installed plugin directories")
    require(all(v["mode"] & 0o444 == 0o444 for v in actual["files"].values()), "Unreadable installed plugin files")
    return actual


def snapshot(root, work):
    before = inventory(root)
    with tarfile.open(work / "previous.tar", "w") as archive:
        for name in sorted([*before["directories"], *before["files"]]):
            archive.add(root / name, arcname=name, recursive=False)
    require(inventory(root) == before, "Plugin volume changed during backup")
    receipt = {"schema": "doris-plugin-backup-v1", **before,
               "archive_sha256": file_hash(work / "previous.tar")}
    verify_backup(work / "previous.tar", receipt)
    write_json(work / "previous.json", receipt)


def verify_backup(path, receipt):
    require(receipt["schema"] == "doris-plugin-backup-v1" and file_hash(path) == receipt["archive_sha256"],
            "Rollback archive checksum mismatch")
    files, directories = {}, {}
    with tarfile.open(path) as archive:
        for entry in archive:
            canonical_path(entry.name)
            require(entry.name not in files and entry.name not in directories, "Duplicate rollback entry")
            if entry.isdir():
                directories[entry.name] = entry.mode
            else:
                require(entry.isfile(), "Nonregular rollback entry")
                import hashlib
                digest = hashlib.sha256()
                with archive.extractfile(entry) as stream:
                    for chunk in iter(lambda: stream.read(65536), b""):
                        digest.update(chunk)
                files[entry.name] = {"sha256": digest.hexdigest(), "mode": entry.mode}
    require(files == receipt["files"] and directories == receipt["directories"], "Rollback archive contents differ")


def install(root, work):
    artifact = prepare(work)
    previous = json.loads((work / "previous.json").read_text())
    require(inventory(root) == {k: previous[k] for k in ("files", "directories")},
            "Stopped plugin volume differs from preserved rollback snapshot")
    # The host confirms stopped FE ownership before starting this worker. No shell,
    # glob or semicolon can mask a failed preparation, backup or checksum check.
    for entry in root.iterdir():
        if entry.is_dir():
            shutil.rmtree(entry)
        else:
            entry.unlink()
    shutil.copytree(work / "candidate" / "lib", root / "lib")
    (root / "lib").chmod(0o755)
    actual = verify_installed(root, artifact)
    write_json(work / "installed.json", {"schema": "doris-plugin-installed-v1",
               "build_id": artifact["build_id"], **actual})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=["prepare", "snapshot", "install", "verify"])
    parser.add_argument("--root", type=Path, default=Path("/dest"))
    parser.add_argument("--work", type=Path, default=Path("/work"))
    args = parser.parse_args()
    if args.operation == "prepare":
        prepare(args.work)
    elif args.operation == "snapshot":
        snapshot(args.root, args.work)
    elif args.operation == "install":
        install(args.root, args.work)
    else:
        artifact = verify_bundle(args.work / "plugin.zip", args.work / "plugin.identity.json")
        write_json(args.work / "verified.json", verify_installed(args.root, artifact))


if __name__ == "__main__":
    main()
