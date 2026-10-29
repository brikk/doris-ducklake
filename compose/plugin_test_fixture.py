"""Tiny, non-executable plugin fixtures for artifact/orchestration tests only."""
import io
import json
import zipfile

from plugin_preflight import API, MANIFEST, PACKAGE, PROVIDER, SERVICE, build_id, records, sha256


def jar_bytes(entries):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as archive:
        for name, data in entries.items():
            archive.writestr(name, data)
    return stream.getvalue()


def write_plugin(path, payload_value=b"compiled-A", dependency=b"dependency-A", package_version=None, api=API):
    payload = {
        PROVIDER.replace(".", "/") + ".class": payload_value,
        SERVICE: (PROVIDER + "\n").encode(),
        "application.txt": b"resource-A",
    }
    runtime = {"lib/example-1.0.jar": jar_bytes({"dependency.txt": dependency})}
    identity = build_id(api, {k: sha256(v) for k, v in payload.items()}, {k: sha256(v) for k, v in runtime.items()})
    # Deliberately fold the long identity; a grep of the first physical line is wrong.
    manifest = ("Manifest-Version: 1.0\r\n" +
                f"Doris-Connector-Plugin-Api-Version: {api}\r\n" +
                f"Implementation-Version: {identity[:40]}\r\n {identity[40:]}\r\n\r\n")
    if package_version is not None:
        manifest += f"Name: {PACKAGE}\r\nImplementation-Version: {package_version}\r\n\r\n"
    provider = "lib/doris-ducklake-test.jar"
    bundle = {provider: jar_bytes({MANIFEST: manifest.encode(), **payload}), **runtime}
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(jar_bytes(bundle))
    sdk = {"fe-connector-spi-1.2-SNAPSHOT.jar": sha256(b"sdk")}
    receipt = {"schema": "doris-plugin-artifact-v1", "api_version": api,
               "build_id": identity, "provider_jar": provider, "provider_jar_sha256": sha256(bundle[provider]),
               "plugin_zip_sha256": sha256(path.read_bytes()), "files": {k: sha256(v) for k, v in bundle.items()},
               "compile_sdk": sdk, "compile_sdk_id": sha256(("doris-compile-sdk-v1\n" + records("S", sdk)).encode())}
    path.with_suffix(".identity.json").write_text(json.dumps(receipt))
    return receipt


def compose_target():
    return {"name": "doris-ducklake-dev", "services": {"doris-fe": {
        "container_name": "doris-ducklake-fe", "image": "doris-fe:ducklake-62e3d81a-api24",
        "volumes": [{"type": "volume", "source": "fe-plugin-ducklake",
                     "target": "/opt/apache-doris/fe/plugins/connector/ducklake"}]}},
        "volumes": {"fe-plugin-ducklake": {"name": "doris-ducklake-api24-62e3d81a-fe-plugin-ducklake"}}}
