"""F24-A: verify/freeze one plugin bundle and inspect its compose target. No Docker mutations."""

import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import zipfile


API = "24.0"
SCHEMA = "doris-plugin-payload-v1"
PROVIDER = "dev.brikk.ducklake.doris.plugin.DuckLakeConnectorProvider"
PACKAGE = "dev/brikk/ducklake/doris/plugin/"
SERVICE = "META-INF/services/org.apache.doris.connector.spi.ConnectorProvider"
MANIFEST = "META-INF/MANIFEST.MF"
PLUGIN_MOUNT = "/opt/apache-doris/fe/plugins/connector/ducklake"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def file_hash(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(64 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_path(name):
    require(name and not name.startswith("/") and "\\" not in name and
            not any(ord(c) < 32 for c in name) and
            not any(p in {"", ".", ".."} for p in name.split("/")),
            f"Noncanonical plugin path: {name!r}")


def records(kind, entries):
    result = []
    for name, digest in sorted(entries.items(), key=lambda entry: entry[0].encode("utf-8")):
        canonical_path(name)
        require(isinstance(digest, str) and re.fullmatch(r"[0-9a-f]{64}", digest), f"Invalid SHA-256: {name}")
        result.append(f"{kind}\t{name}\t{digest}\n")
    return "".join(result)


def build_id(api, payload, runtime):
    canonical = f"{SCHEMA}\nAPI\t{api}\n" + records("P", payload) + records("D", runtime)
    return "doris-ducklake-v1-" + sha256(canonical.encode("utf-8"))


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def manifest_sections(data):
    sections, section = [], {}
    logical = []
    for raw in data.replace(b"\r\n", b"\n").split(b"\n"):
        if raw.startswith(b" "):
            require(logical and logical[-1], "Manifest continuation without a header")
            logical[-1] += raw[1:]
        else:
            logical.append(raw)
    for raw in logical:
        line = raw.decode("utf-8")
        require("\r" not in line, "Malformed manifest line ending")
        if not line:
            if section:
                sections.append(section)
            section = {}
        else:
            name, separator, value = line.partition(": ")
            name = name.lower()
            require(separator and name not in section, "Malformed/duplicate manifest header")
            section[name] = value
    if section:
        sections.append(section)
    require(sections, "Provider manifest is empty")
    return sections


def zip_files(archive):
    result = {}
    for entry in archive.infolist():
        if entry.is_dir():
            continue
        canonical_path(entry.filename)
        require(stat.S_IFMT(entry.external_attr >> 16) in {0, stat.S_IFREG}, "Nonregular ZIP entry")
        require(entry.filename not in result, f"Duplicate ZIP entry: {entry.filename}")
        result[entry.filename] = entry
    return result


def verify_bundle(archive_path, receipt_path):
    receipt = json.loads(receipt_path.read_text(), object_pairs_hook=unique_object)
    require(isinstance(receipt, dict), "Artifact receipt must be a JSON object")
    require(receipt.get("schema") == "doris-plugin-artifact-v1" and receipt.get("api_version") == API,
            "Unsupported artifact schema/API")
    require(file_hash(archive_path) == receipt.get("plugin_zip_sha256"), "Plugin ZIP checksum mismatch")
    with zipfile.ZipFile(archive_path) as archive:
        entries = zip_files(archive)
        require(all(re.fullmatch(r"lib/[^/]+\.jar", name) for name in entries), "Unexpected plugin ZIP layout")
        hashes = {name: sha256(archive.read(entry)) for name, entry in entries.items()}
        require(hashes == receipt.get("files"), "Plugin file set/checksums differ from receipt")
        providers = []
        for name, entry in entries.items():
            with zipfile.ZipFile(io.BytesIO(archive.read(entry))) as jar:
                jar_entries = zip_files(jar)
                if SERVICE in jar_entries:
                    classes = [line.partition("#")[0].strip() for line in jar.read(SERVICE).decode("utf-8").splitlines()]
                    require([c for c in classes if c] == [PROVIDER], "Unexpected connector service provider")
                    providers.append(name)
                    require(PROVIDER.replace(".", "/") + ".class" in jar_entries, "Provider class missing")
                    payload = {path: sha256(jar.read(item)) for path, item in jar_entries.items() if path != MANIFEST}
                    sections = manifest_sections(jar.read(MANIFEST))
        require(len(providers) == 1 and providers[0] == receipt.get("provider_jar"), "Expected exactly one defining provider JAR")
    provider = providers[0]
    require(hashes[provider] == receipt.get("provider_jar_sha256"), "Provider checksum mismatch")
    runtime = {name: digest for name, digest in hashes.items() if name != provider}
    identity = build_id(API, payload, runtime)
    require(identity == receipt.get("build_id"), "Payload identity mismatch")
    main = sections[0]
    require(set(main) == {"manifest-version", "implementation-version", "doris-connector-plugin-api-version"},
            "Unsupported provider manifest attributes")
    require(main["manifest-version"] == "1.0" and main["doris-connector-plugin-api-version"] == API,
            "Provider manifest API mismatch")
    require(main["implementation-version"] == identity, "Provider main identity mismatch")
    names = set()
    for section in sections[1:]:
        require(set(section) == {"name", "implementation-version"} and section["name"] == PACKAGE,
                "Unsupported provider package section")
        require(section["name"] not in names, "Duplicate provider package section")
        names.add(section["name"])
        require(section["implementation-version"] == identity, "Conflicting provider package identity")
    sdk = receipt.get("compile_sdk")
    require(isinstance(sdk, dict) and sdk and all("/" not in name and name.endswith(".jar") for name in sdk),
            "Compile SDK metadata missing/invalid")
    require(sha256(("doris-compile-sdk-v1\n" + records("S", sdk)).encode("utf-8")) == receipt.get("compile_sdk_id"),
            "Compile SDK metadata identity mismatch")
    return receipt


def select_zip(distributions, explicit=None):
    candidates = [Path(explicit)] if explicit else sorted(distributions.glob("doris-ducklake-*-plugin.zip"))
    require(len(candidates) == 1, f"Expected one plugin ZIP, found {len(candidates)}; select --plugin-zip explicitly")
    selected = candidates[0].resolve(strict=True)
    require(selected.is_file() and selected.suffix == ".zip" and not any(c in str(selected) for c in "\t\r\n"),
            "Plugin ZIP path must be a regular .zip without control characters")
    return selected


def docker_output(*args):
    return subprocess.run(["docker", *args], check=True, text=True, capture_output=True).stdout.strip()


def docker_json(*args):
    return json.loads(docker_output(*args))


def target_from_compose(compose_path):
    config = docker_json("compose", "-f", str(compose_path), "config", "--format", "json")
    project = config["name"]
    service = config["services"]["doris-fe"]
    container = service.get("container_name")
    mounts = [m for m in service.get("volumes", []) if m.get("target") == PLUGIN_MOUNT]
    require(len(mounts) == 1 and mounts[0].get("type") == "volume", "Expected one named FE plugin volume")
    key = mounts[0]["source"]
    volume = config["volumes"][key]["name"]
    for name in [project, container, key, volume]:
        require(isinstance(name, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", name), "Unsupported compose target name")
    require(service.get("image"), "Expected an explicit FE image")
    ids = docker_output("container", "ls", "--all", "--filter", f"name=^/{container}$", "--format", "{{.ID}}").splitlines()
    require(len(ids) <= 1, "Ambiguous FE container")
    mounted = False
    if ids:
        instance = docker_json("container", "inspect", ids[0])[0]
        labels = instance["Config"].get("Labels") or {}
        require(instance["Name"].removeprefix("/") == container and
                labels.get("com.docker.compose.project") == project and
                labels.get("com.docker.compose.service") == "doris-fe", "Foreign FE container ownership")
        found = [m for m in instance.get("Mounts", []) if m.get("Destination") == PLUGIN_MOUNT]
        require(len(found) == 1 and found[0].get("Type") == "volume" and found[0].get("Name") == volume,
                "Existing FE uses a different plugin volume")
        mounted = True
    volumes = docker_output("volume", "ls", "--filter", f"name=^{volume}$", "--format", "{{.Name}}").splitlines()
    require(all(name == volume for name in volumes), "Ambiguous plugin volume lookup")
    require(not mounted or volumes, "Existing FE plugin volume was not found")
    if volumes:
        labels = docker_json("volume", "inspect", volume)[0].get("Labels") or {}
        owner, logical = labels.get("com.docker.compose.project"), labels.get("com.docker.compose.volume")
        require(owner in {None, project} and logical in {None, key}, "Foreign plugin volume ownership")
        require(mounted or (owner == project and logical == key), "Orphan/unlabelled plugin volume ownership is unproven")
        consumers = docker_output("container", "ls", "--all", "--filter", f"volume={volume}", "--format", "{{.ID}}").splitlines()
        require(set(consumers) <= set(ids), "Plugin volume is also used by a foreign container")
    return {"project": project, "fe_service": "doris-fe", "fe_container": container,
            "plugin_volume": volume, "plugin_volume_key": key, "fe_image": service["image"],
            "compose_sha256": sha256(json.dumps(config, sort_keys=True, separators=(",", ":")).encode()),
            "fe_present": bool(ids), "volume_present": bool(volumes)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--distributions", required=True, type=Path)
    parser.add_argument("--plugin-zip", type=Path)
    parser.add_argument("--compose-file", required=True, type=Path)
    parser.add_argument("--staging-root", required=True, type=Path)
    args = parser.parse_args()
    stage = None
    try:
        selected = select_zip(args.distributions, args.plugin_zip)
        receipt = selected.with_suffix(".identity.json")
        require(receipt.is_file(), f"Artifact receipt missing: {receipt}; run ./gradlew assemble")
        require(not any(c in str(args.staging_root) for c in "\t\r\n"), "Staging path contains control characters")
        args.staging_root.mkdir(parents=True, exist_ok=True)
        stage = Path(tempfile.mkdtemp(prefix="artifact-", dir=args.staging_root)).resolve()
        frozen, frozen_receipt = stage / selected.name, stage / receipt.name
        shutil.copyfile(selected, frozen)
        shutil.copyfile(receipt, frozen_receipt)
        artifact = verify_bundle(frozen, frozen_receipt)
        target = target_from_compose(args.compose_file.resolve())
        print(json.dumps({"schema": "doris-plugin-preflight-v1", "scope": "artifact-and-target-only",
                          "loaded_provider_verified": False, "stage": str(stage),
                          "selected_zip": str(selected), "staged_zip": str(frozen),
                          "staged_manifest": str(frozen_receipt), "artifact": artifact, "target": target}, sort_keys=True))
    except (ValueError, KeyError, TypeError, OSError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        if stage is not None:
            shutil.rmtree(stage)
        print(f"Plugin preflight failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
