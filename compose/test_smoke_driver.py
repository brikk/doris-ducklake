"""F21/F24 shell orchestration regressions, with no Docker or database access.

The Docker stand-in retains process/claim/volume state and executes the actual
standard-library volume worker on isolated files. SQL remains canned, not Doris
or classloader evidence. Loaded state is snapshotted separately from installed bytes.
"""

import json
import hashlib
import fcntl
import os
from pathlib import Path
import re
import shlex
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest
import zipfile
from plugin_test_fixture import compose_target, write_plugin
import plugin_volume
from plugin_deploy import OWNER
from plugin_preflight import manifest_sections, MANIFEST, SERVICE
from plugin_attest import INVENTORY_SQL


TOKEN = "0123456789abcdef0123456789abcdef"
DATABASE = f"doris_smoke_{TOKEN}"
PREFIX = f"doris-smoke/{TOKEN}/"
DATA_PATH = f"s3://ducklake/{PREFIX}"
MC_ROOT = f"m/ducklake/{PREFIX}"
SOURCE = Path(__file__).resolve().parent
EXISTING_FE_SCENARIOS = {
    "running_fe", "stopped_fe", "stop_failure", "stop_unconfirmed", "prepare_failure", "snapshot_failure",
    "install_failure", "worker_exit_status", "hash_failure", "start_failure", "stale_started_at", "sql_timeout",
    "wrong_existing_image", "foreign_after_claim", "changed_old_volume", "cluster_id_changed", "claim_occupied",
}


def option_values(argv, flag):
    return [argv[i + 1] for i, arg in enumerate(argv[:-1]) if arg == flag]


def fake_docker():
    # Serialize only the stand-in's state transactions, not the production
    # deployment lock. Two real driver processes share this simulated daemon.
    with (Path(os.environ["FAKE_STATE"]) / "daemon.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        return fake_docker_command(lock)


def fake_docker_command(lock):
    argv = sys.argv[2:]
    log = Path(os.environ["FAKE_DOCKER_LOG"])
    state_file = Path(os.environ["FAKE_STATE"]) / "state.json"
    state = json.loads(state_file.read_text()) if state_file.exists() else {"containers": {}, "volume": False, "next": 0}
    previous = [json.loads(line)["argv"] for line in log.read_text().splitlines()]
    with log.open("a") as stream:
        stream.write(json.dumps({
            "argv": argv, "duckdb_version": os.environ.get("DUCKDB_VERSION"),
            "copied_sha256": hashlib.sha256(Path(argv[1]).read_bytes()).hexdigest()
            if argv[0] == "cp" and ":" not in argv[1] else None,
            "attested_before_command": bool(list(Path(os.environ["FAKE_PLUGIN_PATH"]).parents[1].glob(
                "smoke-deployments/deploy-*/deployment-attested.json"))),
            "invocation": os.environ.get("FAKE_INVOCATION", "single"),
            "fe_running": state["containers"].get("fake-fe-id", {}).get("State", {}).get("Running", False),
            "loaded_version": state.get("loaded_version"),
            "worker_operation": state["containers"].get(argv[-1], {}).get("operation") if argv[0] == "start" else None,
        }) + "\n")
    scenario = os.environ["FAKE_SCENARIO"]
    config = compose_target()
    fe_name = "doris-custom-fe" if scenario == "custom_container" else "doris-ducklake-fe"
    volume = config["volumes"]["fe-plugin-ducklake"]["name"]
    volume_root = state_file.parent / "volume"
    volume_root.mkdir(exist_ok=True)

    def save():
        state_file.write_text(json.dumps(state))

    def pause(phase):
        if os.environ.get("FAKE_PAUSE_PHASE") != phase:
            return
        marker = Path(os.environ["FAKE_PAUSED"])
        marker.write_text(phase)
        # Do not block the simulated daemon while this client's request waits.
        fcntl.flock(lock, fcntl.LOCK_UN)
        try:
            deadline = time.monotonic() + 45
            while not Path(os.environ["FAKE_RELEASE"]).exists():
                if time.monotonic() >= deadline:
                    raise RuntimeError("overlap test barrier timed out")
                time.sleep(0.01)
        finally:
            fcntl.flock(lock, fcntl.LOCK_EX)

    def instance(name):
        return next(c for c in state["containers"].values() if name in {c["Id"], c["Name"].lstrip("/")})

    def new_fe():
        return {"Id": "fake-fe-id", "Name": "/" + fe_name, "Image": "fe-image-id",
                "Config": {"Hostname": "doris-fe", "Labels": {"com.docker.compose.project": config["name"],
                           "com.docker.compose.service": "doris-fe"}},
                "State": {"Running": True, "StartedAt": "2026-10-29T12:00:00.000000000Z", "ExitCode": 0},
                "Mounts": [{"Type": "volume", "Name": volume, "Destination": plugin_volume.__dict__.get(
                    "PLUGIN_MOUNT", "/opt/apache-doris/fe/plugins/connector/ducklake"), "RW": True}]}

    def snapshot_loaded_provider():
        for path in volume_root.glob("lib/*.jar"):
            with zipfile.ZipFile(path) as jar:
                if SERVICE in jar.namelist():
                    state["loaded_version"] = manifest_sections(jar.read(MANIFEST))[0]["implementation-version"]
                    break
        if scenario == "stale_provider":
            state["loaded_version"] = os.environ["FAKE_OLD_LOADED_ID"]

    if not state_file.exists() and scenario in EXISTING_FE_SCENARIOS:
        fe = new_fe()
        fe["State"]["StartedAt"] = "2026-10-28T12:00:00.000000000Z"
        fe["State"]["Running"] = scenario != "stopped_fe"
        if scenario == "wrong_existing_image":
            fe["Image"] = "legacy-image-id"
        state["containers"][fe["Id"]] = fe
        state["volume"] = True
        (volume_root / "lib").mkdir(exist_ok=True)
        (volume_root / "lib/stale.jar").write_bytes(b"old-provider")
        (volume_root / ".old-manifest").write_bytes(b"old-manifest")
        save()
    if not state_file.exists() and scenario == "foreign_be":
        state["containers"]["fake-be-id"] = {"Id": "fake-be-id", "Name": "/doris-ducklake-be", "Image": "be-image-id",
            "Config": {"Labels": {"com.docker.compose.project": "another-project", "com.docker.compose.service": "doris-be"}},
            "State": {"Running": True, "StartedAt": "2026-10-28T12:00:00.000000000Z"}, "Mounts": []}
        save()

    if argv[:2] == ["image", "inspect"]:
        image = "helper-image-id" if argv[2] == "python:3.12-slim" else (
            "be-image-id" if "doris-be" in argv[2] or "doris:be-" in argv[2] else "fe-image-id")
        print(json.dumps([{"Id": image}]))
        return 0

    if argv[0] == "compose":
        if "config" in argv:
            if scenario == "mutate_selected_archive":
                Path(os.environ["FAKE_PLUGIN_PATH"]).write_bytes(b"changed-after-freeze")
            config = compose_target()
            config["services"]["doris-fe"]["image"] = os.environ.get("DORIS_FE_IMAGE", config["services"]["doris-fe"]["image"])
            config["services"]["doris-be"]["image"] = os.environ.get("DORIS_BE_IMAGE", config["services"]["doris-be"]["image"])
            if scenario == "custom_container":
                config["services"]["doris-fe"]["container_name"] = "doris-custom-fe"
            print(json.dumps(config))
            return 0
        if "run" in argv and scenario == "bootstrap_failure":
            print("injected bootstrap failure", file=sys.stderr)
            return 1
        if "up" in argv and argv[-1] == "doris-fe":
            state["containers"]["fake-fe-id"] = new_fe()
            snapshot_loaded_provider()
            save()
        if "up" in argv and argv[-1] == "doris-be":
            state["containers"]["fake-be-id"] = {"Id": "fake-be-id", "Name": "/doris-ducklake-be", "Image": "be-image-id",
                "Config": {"Labels": {"com.docker.compose.project": config["name"], "com.docker.compose.service": "doris-be"}},
                "State": {"Running": True, "StartedAt": "2026-10-29T12:00:00.000000000Z"}, "Mounts": []}
            save()
        return 0
    if argv[:2] == ["container", "ls"]:
        if scenario == "foreign_container":
            print("foreign-fe")
        elif any(a.startswith("volume=") for a in argv):
            print("\n".join(c["Id"] for c in state["containers"].values()
                            if any(m.get("Name") == volume for m in c["Mounts"])))
        else:
            name_filter = next(v for v in option_values(argv, "--filter") if v.startswith("name="))
            name = name_filter.removeprefix("name=^/").removesuffix("$")
            print("\n".join(c["Id"] for c in state["containers"].values() if c["Name"] == "/" + name))
        return 0
    if argv[:2] == ["container", "inspect"]:
        if argv[2] == "foreign-fe":
            print(json.dumps([{"Name": "/doris-ducklake-fe", "Config": {"Labels": {
                "com.docker.compose.project": "another-project", "com.docker.compose.service": "doris-fe"}}, "Mounts": []}]))
        else:
            item = instance(argv[2])
            if scenario == "foreign_after_claim" and item["Id"] == "fake-fe-id" and any(
                    OWNER in c["Config"].get("Labels", {}) for c in state["containers"].values()):
                item["Config"]["Labels"]["com.docker.compose.project"] = "racing-foreign-project"
            print(json.dumps([item]))
        return 0
    if argv[:2] == ["volume", "ls"]:
        print(volume if state["volume"] else "")
        return 0
    if argv[:2] == ["volume", "inspect"]:
        print(json.dumps([{"Labels": {"com.docker.compose.project": config["name"],
                                      "com.docker.compose.volume": "fe-plugin-ducklake"}}]))
        return 0
    if argv[:2] == ["volume", "create"]:
        state["volume"] = True
        save()
        return 0
    if argv[0] == "cp":
        def mapped(path):
            if ":" not in path:
                return Path(path)
            container, destination = path.split(":", 1)
            return state_file.parent / instance(container)["Id"] / destination.lstrip("/")
        source, destination = map(mapped, argv[1:3])
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
        return 0
    if argv[0] == "create":
        if "--name" in argv and (scenario == "claim_occupied" or any(
                c["Name"] == "/" + option_values(argv, "--name")[0] for c in state["containers"].values())):
            print("occupied deployment claim", file=sys.stderr)
            return 1
        state["next"] += 1
        identifier = "fake-helper-" + str(state["next"])
        labels = dict(v.split("=", 1) for v in option_values(argv, "--label"))
        name = option_values(argv, "--name") or [identifier]
        state["containers"][identifier] = {"Id": identifier, "Name": "/" + name[0], "Image": "helper-image-id",
            "Config": {"Labels": labels}, "State": {"Running": False, "ExitCode": 0, "StartedAt": ""},
            "Mounts": [{"Name": volume}] if "-v" in argv else [], "operation": argv[-1] if "-v" in argv else None}
        save()
        print(identifier)
        return 0
    if argv[0] == "start":
        container = instance(argv[-1])
        operation = container.get("operation")
        if operation:
            work = state_file.parent / container["Id"] / "work"
            try:
                if scenario == "snapshot_failure" and operation == "snapshot":
                    raise OSError("injected backup failure")
                if scenario == "install_failure" and operation == "install":
                    (volume_root / "partial-write").write_bytes(b"uncertain")
                    raise OSError("injected install failure")
                if scenario == "worker_exit_status" and operation == "install":
                    (volume_root / "partial-write").write_bytes(b"worker-failed-with-successful-cli")
                    container["State"]["ExitCode"] = 19
                    save()
                    return 0  # Docker client success is not worker success.
                if scenario == "changed_old_volume" and operation == "install":
                    (volume_root / "lib/stale.jar").write_bytes(b"changed-after-snapshot")
                if operation == "snapshot":
                    plugin_volume.snapshot(volume_root, work)
                elif operation == "install":
                    plugin_volume.install(volume_root, work)
                    if scenario == "hash_failure":
                        (volume_root / "lib/extra.jar").write_bytes(b"unattested-extra")
                else:
                    artifact = json.loads((work / "plugin.identity.json").read_text())
                    actual = plugin_volume.verify_installed(volume_root, artifact)
                    plugin_volume.write_json(work / "verified.json", actual)
            except Exception as error:
                container["State"]["ExitCode"] = 1
                print(str(error), file=sys.stderr)
                save()
                return 1
        else:
            if container["Id"] == "fake-fe-id" and scenario == "start_failure":
                print("injected start failure", file=sys.stderr)
                return 1
            container["State"]["Running"] = True
            if container["Id"] != "fake-fe-id" or scenario != "stale_started_at":
                container["State"]["StartedAt"] = "2026-10-29T12:00:00.000000000Z"
            if container["Id"] == "fake-fe-id":
                snapshot_loaded_provider()
        save()
        return 0
    if argv[0] == "stop":
        if argv[-1] == "fake-fe-id" and scenario == "stop_failure":
            print("injected stop failure", file=sys.stderr)
            return 1
        if argv[-1] == "fake-fe-id" and scenario == "stop_unconfirmed":
            return 0
        instance(argv[-1])["State"]["Running"] = False
        save()
        return 0
    if argv[0] == "rm":
        del state["containers"][instance(argv[-1])["Id"]]
        save()
        return 0
    if argv[0] == "exec" and argv[2:4] == ["python3", "/work/plugin_volume.py"]:
        if scenario == "prepare_failure":
            print("injected candidate preparation failure", file=sys.stderr)
            return 1
        work = state_file.parent / instance(argv[1])["Id"] / "work"
        plugin_volume.prepare(work)
        return 0
    if argv[0] == "exec" and argv[2] == "cat":
        restarted = instance(argv[1])["State"]["StartedAt"].startswith("2026-10-29")
        print("clusterId=" + ("999" if scenario == "cluster_id_changed" and restarted else "123"))
        return 0
    if argv[:3] == ["exec", "trino-ducklake-postgres", "psql"]:
        sql = " ".join(option_values(argv, "-c")[0].split())
        if "gen_random_uuid()" in sql:
            pause("uuid")
            print(os.environ["FAKE_TOKEN"])
        elif sql.startswith("CREATE DATABASE"):
            if scenario == "duplicate_database":
                print("database already exists", file=sys.stderr)
                return 1
        elif "key = 'data_path'" in sql:
            print("s3://ducklake/data/" if scenario == "wrong_path" else DATA_PATH)
        elif "COUNT(*) FROM ducklake_snapshot" in sql:
            print(1 if argv in previous else 3)
        elif "COUNT(*) FROM ducklake_schema" in sql:
            print(0 if argv in previous else 1)
        elif "COUNT(*) FROM ducklake_files_scheduled_for_deletion" in sql:
            print(0)
        elif "SELECT pc.transform" in sql:
            print("bucket(4)")
        elif "SELECT pv.partition_value" in sql:
            print("1\n2\n3")
        else:
            print(1)
        return 0
    if argv[0] == "exec" and argv[1] in {fe_name, "fake-fe-id"} and argv[2] == "mysql":
        sql = " ".join(option_values(argv, "-e")[0].split())
        if scenario == "sql_timeout" and sql == "SHOW FRONTENDS":
            return 1
        if "information_schema.extensions" in sql:
            pause("inventory")
            if not state["containers"].get("fake-be-id", {}).get("State", {}).get("Running", False):
                print("No backend available as scan node", file=sys.stderr)
                return 1
            if scenario == "inventory_error":
                print("injected inventory SQL error", file=sys.stderr)
                return 1
            if scenario == "missing_provider":
                return 0
            version = state.get("loaded_version", "NULL")
            if scenario == "null_provider":
                version = "NULL"
            row = ["ducklake", "FILESYSTEM" if scenario == "wrong_provider_type" else "CONNECTOR", version,
                   "BUILTIN" if scenario == "builtin_provider" else "UNKNOWN" if scenario == "unknown_provider_source" else "EXTERNAL"]
            if scenario == "malformed_provider":
                print("ducklake\tCONNECTOR\ttruncated")
                return 0
            print("\t".join(row))
            if scenario == "duplicate_provider":
                print("\t".join(row))
            if scenario == "conflicting_provider":
                print("\t".join(["ducklake", "CONNECTOR", "doris-ducklake-v1-" + "f" * 64, "EXTERNAL"]))
            if scenario == "process_changed_after_inventory":
                state["containers"]["fake-fe-id"]["State"]["StartedAt"] = "2026-10-29T13:00:00.000000000Z"
                save()
            if scenario == "disk_changed_after_inventory":
                next(volume_root.glob("lib/*.jar")).write_bytes(b"changed-after-load")
            return 0
        if "-B" in argv and sql in {"SHOW FRONTENDS", "SHOW BACKENDS"}:
            if scenario == "be_registration_timeout" and sql == "SHOW BACKENDS":
                return 0
            print("Alive\tWrongColumn" if scenario == "bad_source_headers" else "Alive\tVersion")
            version = "doris-0.0.0-62e3d81a42b"
            if scenario == "stale_master_be" and sql == "SHOW BACKENDS":
                version = "doris-0.0.0-96d0ac68e84"
            if scenario == "stale_matched_pair":
                version = "doris-0.0.0-96d0ac68e84"
            if sql == "SHOW BACKENDS" and os.environ.get("DORIS_BE_IMAGE") == "apache/doris:be-4.1.4":
                version = "doris-4.1.4-stock"
            print(f"true\t{version}")
        elif sql == "SELECT 1 AS wire_compat_ok":
            print(0 if scenario == "runtime_select_failure" else 1)
        elif sql == "SHOW BACKENDS":
            print("\t".join(["0"] * 9 + ["true"]))
        elif " EXECUTE " in sql:
            print("ok\t0\t2\t0" if "expire_snapshots(" in sql else "ok\t0\t1\t0")
        elif "EXPLAIN" in sql:
            print("orders scan")
        elif "SELECT b FROM" in sql and ".default_probe" in sql:
            print(99)
        elif "SELECT COUNT(" in sql:
            if ".count_col_check" in sql:
                print(2 if "COUNT(v)" in sql else 4)
            elif ".step7_orders" in sql:
                print(100 if scenario == "wrong_delete_count" else 93)
            elif ".default_probe" in sql:
                print(0 if "IS NULL" in sql or scenario == "wrong_default_count" else 3)
            elif ".doris_ddl" in sql:
                print(2)
            elif "WHERE name='alice'" in sql:
                print(1)
            else:
                print(3)
        return 0
    if argv[0] == "run" and "python:3.12-slim" in argv:
        return 0
    if argv[0] == "run" and "minio/mc:latest" in argv:
        command = argv[-1]
        if "mc ls " in command:
            if scenario == "prefix_error":
                print("injected prefix listing failure", file=sys.stderr)
                return 1
            if scenario == "occupied_prefix":
                print("existing.parquet")
        elif "mc stat " in command:
            print("gone" if "ducklake-smoke-" in command else "present")
        return 0
    print(f"Unhandled fake Docker command: {argv!r}", file=sys.stderr)
    return 99


class SmokeDriverTests(unittest.TestCase):
    def test_default_requires_matching_master_and_preserves_complete_plugin_layout(self):
        compose = (SOURCE / "docker-compose.yml").read_text()
        overlay = (SOURCE / "be-overlay" / "Dockerfile").read_text()
        self.assertIn("image: ${DORIS_FE_IMAGE:-doris-fe:ducklake-62e3d81a-api24}", compose)
        self.assertIn("image: ${DORIS_BE_IMAGE:-doris-be:ducklake-62e3d81a-api24-asan}", compose)
        volume = "doris-ducklake-api24-62e3d81a-fe-plugin-ducklake"
        self.assertIn(f"name: {volume}", compose)
        self.assertIn("plugin_preflight.py", (SOURCE / "smoke.sh").read_text())
        self.assertIn("name: doris-ducklake-api24-62e3d81a-fe-meta", compose)
        self.assertIn("name: doris-ducklake-api24-62e3d81a-be-storage", compose)
        self.assertNotIn("apache/doris:be-4.1.3", compose)
        self.assertIn("ARG BASE_IMAGE=apache/doris:be-4.1.4", overlay)
        self.assertIn("COPY ${OUTPUT_PATH}/be/plugins ${DORIS_BE_HOME}/plugins", overlay)

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="smoke-driver-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.compose = self.root / "doris-ducklake" / "compose"
        self.substrate = self.root / "trino-ducklake" / "compose" / "docker-compose.yml"
        self.compose.mkdir(parents=True)
        self.substrate.parent.mkdir(parents=True)
        self.substrate.write_text("services: {}\n")
        (self.compose / "docker-compose.yml").write_text("services: {}\n")
        for name in ("smoke.sh", "fe.conf", "smoke_lake.py", "step7-delete.py",
                      "step-default.py", "w2-insert.py", "plugin_preflight.py", "plugin_deploy.py",
                       "plugin_volume.py", "plugin_attest.py", "smoke_run.sh"):
            shutil.copyfile(SOURCE / name, self.compose / name)
        distributions = self.compose.parent / "build" / "distributions"
        distributions.mkdir(parents=True)
        self.plugin = distributions / "doris-ducklake-test-plugin.zip"
        self.artifact = write_plugin(self.plugin)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        docker = self.bin / "docker"
        docker.write_text(
            "#!/bin/sh\nexec " + shlex.join([sys.executable, str(Path(__file__).resolve()),
                                            "--fake-docker"]) + ' "$@"\n'
        )
        docker.chmod(0o755)
        sleep = self.bin / "sleep"
        sleep.write_text("#!/bin/sh\nexit 0\n")
        sleep.chmod(0o755)
        self.log = self.root / "docker.jsonl"

    def driver_environment(self, fake_state, scenario="ok", token=TOKEN, be_image=None, fe_image=None):
        if scenario in EXISTING_FE_SCENARIOS:
            (self.compose / ".fe.conf.runtime").write_bytes((self.compose / "fe.conf").read_bytes()
                + b"\npriority_networks = 172.30.80.0/24\n")
        # Do not inherit BASH_ENV, exported functions, Docker settings, etc.
        env = {
            "PATH": str(self.bin) + os.pathsep + os.defpath,
            "HOME": str(self.root), "LC_ALL": "C",
            "PYTHONDONTWRITEBYTECODE": "1",
            "DORIS_CORPUS_DIR": str(self.root / "corpus"),
            "FAKE_DOCKER_LOG": str(self.log), "FAKE_SCENARIO": scenario,
            "FAKE_TOKEN": token,
            "FAKE_PLUGIN_PATH": str(self.plugin),
            "FAKE_OLD_LOADED_ID": self.artifact["build_id"],
            "FAKE_STATE": str(fake_state),
            "DORIS_FE_STARTUP_TIMEOUT": "1",
            # Inherited targets must not let a caller adopt the shared lake.
            "SMOKE_RUN_ID": "f" * 32, "SMOKE_PG_DB": "ducklake",
            "SMOKE_CATALOG": "dl", "SMOKE_PREFIX": "data/",
            "SMOKE_DATA_PATH": "s3://ducklake/data/", "SMOKE_MC_ROOT": "m/ducklake/data/",
            "PG_DB": "ducklake", "DATA_PATH": "s3://ducklake/data/",
            "S3_DATA_PREFIX": "data/", "DUCKDB_VERSION": "must-be-overridden",
        }
        if be_image is not None:
            env["DORIS_BE_IMAGE"] = be_image
        if fe_image is not None:
            env["DORIS_FE_IMAGE"] = fe_image
        return env

    def run_driver(self, *args, scenario="ok", token=TOKEN, be_image=None, fe_image=None):
        self.log.write_text("")
        fake_state = Path(tempfile.mkdtemp(prefix="fake-daemon-", dir=self.root))
        self.fake_state = fake_state
        env = self.driver_environment(fake_state, scenario, token, be_image, fe_image)
        result = subprocess.run(
            ["bash", str(self.compose / "smoke.sh"), "--no-build", *args],
            cwd=self.root, env=env, text=True, capture_output=True, timeout=30,
        )
        records = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.records = records
        self.calls = [record["argv"] for record in records]
        self.output = result.stdout + result.stderr
        runtime = self.compose / ".fe.conf.runtime"
        if runtime.exists():
            self.assertEqual(runtime.read_bytes(), (self.compose / "fe.conf").read_bytes()
                             + b"\npriority_networks = 172.30.80.0/24\n")
        else:
            self.assertFalse(any(argv[:2] == ["volume", "create"] or "up" in argv for argv in self.calls))
        return result

    def mysql_sql(self):
        return [option_values(argv, "-e")[0] for argv in self.calls
                if argv[0] == "exec" and len(argv) > 2 and argv[2] == "mysql"]

    def allocation_calls(self):
        stages = []
        for argv in self.calls:
            if argv[:3] == ["exec", "trino-ducklake-postgres", "psql"]:
                sql = option_values(argv, "-c")[0]
                if "gen_random_uuid()" in sql:
                    stages.append(("uuid", argv))
                elif "CREATE DATABASE" in sql:
                    stages.append(("database", argv))
                elif "key = 'data_path'" in sql:
                    stages.append(("path", argv))
            elif argv[0] == "compose" and "run" in argv:
                stages.append(("bootstrap", argv))
            elif argv[0] == "run" and "minio/mc:latest" in argv:
                if "mc mb " in argv[-1]:
                    stages.append(("bucket", argv))
                elif "mc ls " in argv[-1]:
                    stages.append(("prefix", argv))
        return stages

    def assert_no_catalog_mutation(self):
        for sql in self.mysql_sql():
            self.assertNotRegex(sql, r"(?i)\b(CREATE|DROP|INSERT|DELETE|ALTER|REFRESH|SWITCH|USE)\b")

    def assert_infrastructure_only_up(self):
        ups = [argv for argv in self.calls if argv[0] == "compose" and "up" in argv]
        self.assertEqual(ups, [
            ["compose", "-f", str(self.substrate), "up", "-d", "--no-recreate",
             "--wait", "--wait-timeout", "120", "postgres", "minio"],
            ["compose", "-f", str(self.compose / "docker-compose.yml"), "up", "-d", "--no-deps", "--no-build",
             "--pull", "never", "doris-fe"],
            ["compose", "-f", str(self.compose / "docker-compose.yml"), "up", "-d", "--no-deps", "--no-build",
             "--pull", "never", "doris-be"],
        ])

    def test_full_run_owns_all_targets(self):
        self.assertEqual(self.run_driver().returncode, 0, self.output)
        self.assert_infrastructure_only_up()
        stages = self.allocation_calls()
        self.assertEqual([name for name, _ in stages],
                         ["uuid", "bucket", "prefix", "database", "bootstrap", "path"])
        stage_indexes = [self.calls.index(argv) for _, argv in stages]
        self.assertEqual(stage_indexes, sorted(stage_indexes))
        copied = [record for record in self.records if record["argv"][0] == "cp"
                  and record["argv"][2].endswith(":/work/plugin.zip")]
        self.assertEqual(len(copied), 4)
        self.assertTrue(all(r["copied_sha256"] == self.artifact["plugin_zip_sha256"] for r in copied))
        be_up = next(i for i, a in enumerate(self.calls) if a[0] == "compose" and "up" in a and a[-1] == "doris-be")
        early_sql = [a for a in self.calls[:be_up] if a[0] == "exec" and len(a) > 2 and a[2] == "mysql"]
        self.assertEqual([option_values(a, "-e")[0] for a in early_sql], ["SHOW FRONTENDS"])
        inventory_index = next(i for i, a in enumerate(self.calls) if a[0] == "exec" and
                               len(a) > 2 and a[2] == "mysql" and "information_schema.extensions" in a[-1])
        self.assertGreater(inventory_index, be_up)
        self.assertEqual(self.calls[inventory_index][-1], INVENTORY_SQL)

        postgres = [argv for argv in self.calls
                    if argv[:3] == ["exec", "trino-ducklake-postgres", "psql"]]
        self.assertGreater(len(postgres), 10)
        for argv in postgres:
            sql = option_values(argv, "-c")[0]
            admin = "gen_random_uuid()" in sql or sql.startswith("CREATE DATABASE")
            self.assertEqual(option_values(argv, "-d"), ["postgres" if admin else DATABASE])
            self.assertIn("-X", argv)
            self.assertIn("ON_ERROR_STOP=1", option_values(argv, "-v"))
        self.assertEqual(option_values(stages[3][1], "-c"),
                         [f"CREATE DATABASE {DATABASE} TEMPLATE template0;"])

        bootstrap = stages[4][1]
        self.assertEqual(bootstrap[:4], ["compose", "-f", str(self.substrate), "run"])
        self.assertEqual(bootstrap[-1], "bootstrap")
        self.assertIn("--rm", bootstrap)
        self.assertIn("--no-deps", bootstrap)
        overrides = option_values(bootstrap, "-e")
        self.assertCountEqual(overrides, [
            "PG_HOST=trino-ducklake-postgres", f"PG_DB={DATABASE}",
            "PG_USER=ducklake", "PG_PASSWORD=ducklake",
            "S3_ENDPOINT=trino-ducklake-minio:9000", "S3_KEY_ID=minioadmin",
            "S3_SECRET=minioadmin", "S3_BUCKET=ducklake", f"S3_DATA_PREFIX={PREFIX}",
            "TPCH_SCHEMA=tpch", "TPCH_SCALE_FACTOR=0.01",
        ])
        self.assertEqual(self.records[stage_indexes[4]]["duckdb_version"], "1.5.6")

        helpers = [argv for argv in self.calls if argv[0] == "run" and "python:3.12-slim" in argv]
        helper_scripts = []
        verify_tables = []
        for argv in helpers:
            mounts = option_values(argv, "-v")
            self.assertIn(f"{self.compose}/smoke_lake.py:/smoke_lake.py:ro", mounts)
            script_mounts = [mount for mount in mounts if mount.endswith(":/script.py:ro")]
            self.assertEqual(len(script_mounts), 1)
            script = Path(script_mounts[0].split(":")[0])
            self.assertEqual(script.parent, self.compose)
            helper_scripts.append(script.name)
            self.assertEqual(option_values(argv, "--network"), ["trino-ducklake-dev_default"])
            values = option_values(argv, "-e")
            env = dict(value.split("=", 1) for value in values)
            self.assertEqual(len(values), len(env), "duplicate helper environment override")
            for key, expected in {
                "SMOKE_RUN_ID": TOKEN, "PG_DB": DATABASE, "DATA_PATH": DATA_PATH,
                "PG_HOST": "trino-ducklake-postgres", "PG_USER": "ducklake",
                "PG_PASSWORD": "ducklake", "S3_ENDPOINT": "trino-ducklake-minio:9000",
                "S3_KEY_ID": "minioadmin", "S3_SECRET": "minioadmin",
            }.items():
                self.assertEqual(env.get(key), expected, (script.name, key))
            if script.name == "w2-insert.py":
                self.assertEqual(env["MODE"], "verify")
                verify_tables.append((env["SCHEMA"], env["TABLE"]))
            elif script.name == "step7-delete.py":
                self.assertEqual(env["DELETE_COUNT"], "7")
        self.assertCountEqual(helper_scripts, ["step7-delete.py", "step-default.py"] + ["w2-insert.py"] * 5)
        self.assertCountEqual(verify_tables, [
            ("ddl_smoke", "doris_ddl"), ("ddl_smoke", "doris_ddl"),
            ("tpch", "doris_w"), ("tpch", "doris_wb"), ("tpch", "doris_ctas"),
        ])

        sql_calls = self.mysql_sql()
        creates = [sql for sql in sql_calls if "CREATE CATALOG" in sql]
        self.assertEqual(len(creates), 2)
        first_create = next(i for i, argv in enumerate(self.calls)
                            if argv[:3] == ["exec", "fake-fe-id", "mysql"]
                            and "CREATE CATALOG" in option_values(argv, "-e")[0])
        self.assertGreater(first_create, stage_indexes[-1])
        for sql in creates:
            self.assertRegex(sql, rf"CREATE CATALOG {DATABASE}\b")
            self.assertEqual(re.findall(r"'metadata.url'\s*=\s*'([^']+)'", sql),
                             [f"jdbc:postgresql://trino-ducklake-postgres:5432/{DATABASE}"])
            self.assertEqual(re.findall(r"'storage.warehouse'\s*=\s*'([^']+)'", sql), [DATA_PATH])
        for sql in sql_calls:
            sql = re.sub(r"--[^\n]*", "", sql)
            self.assertNotRegex(sql, r"(?i)\bdl\b")
            targets = re.findall(r"(?i)\b(?:CREATE CATALOG|DROP CATALOG|REFRESH CATALOG|SWITCH|USE)\s+(\w+)", sql)
            targets += re.findall(r"\b(\w+)\.\w+\.\w+\b", sql)
            for target in targets:
                self.assertEqual(target, DATABASE, sql)
        for argv in self.calls[first_create:]:
            if argv[:3] == ["exec", "fake-fe-id", "mysql"]:
                self.assertIn(DATABASE, option_values(argv, "-e")[0])

        mc_commands = [argv[-1] for argv in self.calls if "minio/mc:latest" in argv]
        object_paths = []
        for command in mc_commands:
            for path in re.findall(r"m/[^\s'\";]+", command):
                if path == "m/ducklake" and "mc mb --ignore-existing m/ducklake" in command:
                    continue
                self.assertTrue(path.startswith(MC_ROOT), path)
                self.assertNotIn("..", path.split("/"))
                object_paths.append(path)
        for name in ("ducklake-smoke-cleanup.parquet", "ducklake-smoke-orphan.parquet",
                     "_SUCCESS", "data.parquet"):
            self.assertIn(MC_ROOT + name, object_paths)
        schedule_sql = [option_values(argv, "-c")[0] for argv in postgres
                        if "INSERT INTO ducklake_files_scheduled_for_deletion" in argv[-1]]
        self.assertEqual(len(schedule_sql), 1)
        self.assertEqual(re.findall(r"s3://[^']+", schedule_sql[0]),
                         [DATA_PATH + "ducklake-smoke-cleanup.parquet"])
        procedures = [sql for sql in sql_calls if " EXECUTE " in sql]
        self.assertEqual(len(procedures), 3)
        for sql, procedure in zip(procedures, ("expire_snapshots", "cleanup_old_files", "remove_orphan_files")):
            self.assertIn(f"ALTER TABLE {DATABASE}.tpch.orders EXECUTE {procedure}(", sql)
        last_mc = [argv for argv in self.calls if "minio/mc:latest" in argv][-1]
        self.assertIn(f"mc rm {MC_ROOT}_SUCCESS", last_mc[-1])

    def test_wrong_delete_count_fails_the_smoke(self):
        self.assertNotEqual(self.run_driver(scenario="wrong_delete_count").returncode, 0)
        self.assertIn("Step 7 FAIL: Doris returned 100 rows, expected 93", self.output)
        _, evidence = self.lifecycle()
        self.assertFalse((evidence / "deployment-attested.json").exists())
        self.assertTrue((evidence / "deployment-attested-before-error.json").is_file())
        self.assertFalse(json.loads((evidence / "run-result.json").read_text())["run_success"])

    def test_wrong_default_count_fails_and_retains_the_fixture(self):
        self.assertNotEqual(self.run_driver(scenario="wrong_default_count").returncode, 0)
        self.assertIn("DEFAULT-BACKFILL FAIL", self.output)
        self.assertNotIn("Smoke complete", self.output)
        self.assertFalse(any("DROP TABLE IF EXISTS tpch.default_probe" in sql for sql in self.mysql_sql()))

    def test_up_only_does_not_seed_or_mutate_catalogs(self):
        self.assertEqual(self.run_driver("--up-only").returncode, 0, self.output)
        self.assert_infrastructure_only_up()
        self.assert_no_catalog_mutation()
        self.assertTrue(any("SHOW BACKENDS" in sql for sql in self.mysql_sql()))
        self.assertTrue(any("enable_local_shuffle_planner = true" in sql for sql in self.mysql_sql()))
        self.assertFalse(any("enable_local_shuffle_planner = false" in sql for sql in self.mysql_sql()))
        self.assertEqual(self.allocation_calls(), [])
        self.assertFalse(any(argv[0] == "run" or "psql" in argv for argv in self.calls))

    def test_matching_master_be_skips_release_local_shuffle_shim(self):
        self.assertEqual(
            self.run_driver("--up-only", be_image="doris-be:master-local").returncode,
            0,
            self.output,
        )
        self.assertTrue(any("enable_local_shuffle_planner = true" in sql for sql in self.mysql_sql()))
        self.assertFalse(any("enable_local_shuffle_planner = false" in sql for sql in self.mysql_sql()))

    def test_preflight_only_reads_docker_and_does_not_stage_live_config_or_install(self):
        self.assertEqual(self.run_driver("--preflight-only").returncode, 0, self.output)
        result = json.loads(self.output)
        self.assertEqual(result["artifact"]["build_id"], self.artifact["build_id"])
        self.assertEqual(result["target"]["fe_container"], "doris-ducklake-fe")
        self.assertFalse(Path(result["stage"]).exists())
        self.assertFalse((self.compose / ".fe.conf.runtime").exists())
        self.assertEqual(self.allocation_calls(), [])
        self.assertTrue(all(argv[0] == "compose" and "config" in argv or argv[1] in {"ls", "inspect"}
                            for argv in self.calls))

    def test_help_lists_new_artifact_flags_without_docker_access(self):
        self.assertEqual(self.run_driver("--help").returncode, 0, self.output)
        self.assertIn("--preflight-only", self.output)
        self.assertIn("--plugin-zip", self.output)
        self.assertEqual(self.calls, [])

    def test_malformed_selection_flags_fail_without_docker_access(self):
        for args in (("--plugin-zip",), ("--down", "--preflight-only"),
                     ("--down", "--plugin-zip", str(self.plugin))):
            with self.subTest(args=args):
                self.assertNotEqual(self.run_driver(*args).returncode, 0)
                self.assertEqual(self.calls, [])

    def test_missing_or_ambiguous_archive_fails_before_any_docker_command(self):
        other = self.plugin.with_name("doris-ducklake-other-plugin.zip")
        write_plugin(other)
        self.assertNotEqual(self.run_driver("--up-only").returncode, 0)
        self.assertIn("found 2", self.output)
        self.assertEqual(self.calls, [])
        self.plugin.unlink()
        other.unlink()
        self.assertNotEqual(self.run_driver("--up-only").returncode, 0)
        self.assertIn("found 0", self.output)
        self.assertEqual(self.calls, [])

    def test_explicit_archive_path_with_spaces_wins_over_multiple_candidates(self):
        other = self.plugin.with_name("doris-ducklake-other-plugin.zip")
        write_plugin(other)
        selected = self.root / "explicit artifacts" / "chosen-plugin.zip"
        expected = write_plugin(selected, payload_value=b"chosen-build")
        self.assertEqual(self.run_driver("--up-only", "--plugin-zip", str(selected)).returncode, 0, self.output)
        copied = next(r for r in self.records if r["argv"][0] == "cp" and r["argv"][2].endswith(":/work/plugin.zip"))
        self.assertEqual(copied["copied_sha256"], expected["plugin_zip_sha256"])

    def test_missing_explicit_archive_or_receipt_and_corruption_fail_before_docker(self):
        self.assertNotEqual(self.run_driver("--plugin-zip", str(self.root / "missing.zip")).returncode, 0)
        self.assertEqual(self.calls, [])
        self.plugin.with_suffix(".identity.json").unlink()
        self.assertNotEqual(self.run_driver("--up-only").returncode, 0)
        self.assertIn("receipt missing", self.output)
        self.assertEqual(self.calls, [])
        write_plugin(self.plugin)
        self.plugin.write_bytes(self.plugin.read_bytes() + b"corrupt")
        self.assertNotEqual(self.run_driver("--up-only").returncode, 0)
        self.assertIn("ZIP checksum", self.output)
        self.assertEqual(self.calls, [])

    def test_foreign_fe_collision_fails_before_any_mutation(self):
        self.assertNotEqual(self.run_driver("--up-only", scenario="foreign_container").returncode, 0)
        self.assertIn("Foreign FE container", self.output)
        self.assertEqual(self.allocation_calls(), [])
        self.assert_no_catalog_mutation()
        self.assertTrue(all(argv[0] == "compose" and "config" in argv or argv[1] in {"ls", "inspect"}
                            for argv in self.calls))

    def test_rendered_image_override_is_reported_without_deployment(self):
        self.assertEqual(self.run_driver("--preflight-only", fe_image="example/fe:selected").returncode, 0, self.output)
        self.assertEqual(json.loads(self.output)["target"]["fe_image"], "example/fe:selected")

    def test_all_fe_commands_use_the_rendered_container(self):
        self.assertEqual(self.run_driver(scenario="custom_container").returncode, 0, self.output)
        self.assertTrue(self.mysql_sql())
        self.assertTrue(all(argv[1] in {"doris-custom-fe", "fake-fe-id"} for argv in self.calls
                            if argv[0] == "exec" and len(argv) > 2 and argv[2] == "mysql"))

    def test_copy_uses_frozen_bytes_if_another_build_replaces_the_selected_path(self):
        self.assertEqual(self.run_driver("--up-only", scenario="mutate_selected_archive").returncode, 0, self.output)
        self.assertEqual(self.plugin.read_bytes(), b"changed-after-freeze")
        copied = next(r for r in self.records if r["argv"][0] == "cp" and r["argv"][2].endswith(":/work/plugin.zip"))
        self.assertEqual(copied["copied_sha256"], self.artifact["plugin_zip_sha256"])

    def lifecycle(self):
        paths = list((self.compose.parent / "build/smoke-deployments").glob("deploy-*/lifecycle.json"))
        self.assertEqual(len(paths), 1)
        return json.loads(paths[0].read_text()), paths[0].parent

    def assert_stopped_write_order(self):
        writes = [r for r in self.records if r["argv"][:2] == ["volume", "create"] or r["worker_operation"] == "install"]
        self.assertTrue(writes)
        self.assertTrue(all(not r["fe_running"] for r in writes))
        claim = next(i for i, r in enumerate(self.records) if r["argv"][0] == "create" and "--name" in r["argv"])
        self.assertLess(claim, min(self.records.index(r) for r in writes))

    def overlapping_invocations(self, phase):
        shared = Path(tempfile.mkdtemp(prefix="overlap-daemon-", dir=self.root))
        self.log.write_text("")
        paused, release = self.root / "paused", self.root / "release"
        candidate_b = self.root / "candidate-b.zip"
        b = write_plugin(candidate_b, payload_value=b"second-client-different-build")
        self.assertNotEqual(b["build_id"], self.artifact["build_id"])
        first_env = self.driver_environment(shared)
        first_env.update(FAKE_INVOCATION="first", FAKE_PAUSE_PHASE=phase,
                         FAKE_PAUSED=str(paused), FAKE_RELEASE=str(release))
        second_env = self.driver_environment(shared)
        second_env["FAKE_INVOCATION"] = "second"
        children = []
        try:
            first = subprocess.Popen(["bash", str(self.compose / "smoke.sh"), "--plugin-zip", str(self.plugin)],
                                     cwd=self.root, env=first_env, text=True, stdout=subprocess.PIPE,
                                     stderr=subprocess.PIPE, start_new_session=True)
            children.append(first)
            deadline = time.monotonic() + 45
            while not paused.exists():
                if first.poll() is not None:
                    self.fail("First invocation exited before overlap barrier: " + str(first.communicate()))
                self.assertLess(time.monotonic(), deadline, "First invocation never reached overlap barrier")
                time.sleep(0.01)
            self.assertEqual(paused.read_text(), phase)
            before = json.loads((shared / "state.json").read_text())
            second = subprocess.Popen(["bash", str(self.compose / "smoke.sh"), "--plugin-zip", str(candidate_b)],
                                      cwd=self.root, env=second_env, text=True, stdout=subprocess.PIPE,
                                      stderr=subprocess.PIPE, start_new_session=True)
            children.append(second)
            second_out, second_err = second.communicate(timeout=35)
            self.assertNotEqual(second.returncode, 0, second_out + second_err)
            self.assertIn("claim occupied", second_err)
            after = json.loads((shared / "state.json").read_text())
            self.assertEqual(before, after, "Rival mutated the claimed daemon state")
            self.assertEqual(after["loaded_version"], self.artifact["build_id"])
            self.assertEqual({k: v["sha256"] for k, v in plugin_volume.inventory(shared / "volume")["files"].items()},
                             self.artifact["files"])
            release.touch()
            first_out, first_err = first.communicate(timeout=45)
            self.assertEqual(first.returncode, 0, first_out + first_err)
            records = [json.loads(line) for line in self.log.read_text().splitlines()]
            rival = [r["argv"] for r in records if r["invocation"] == "second"]
            self.assertFalse(any(a[0] in {"stop", "start", "cp", "rm", "exec", "run"} or
                                 a[:2] == ["volume", "create"] or "up" in a for a in rival))
            self.records = [r for r in records if r["invocation"] == "first"]
            self.calls = [r["argv"] for r in self.records]
            self.assert_stopped_write_order()
            inventory = next(i for i, a in enumerate(self.calls) if a[0] == "exec" and "information_schema.extensions" in a[-1])
            uuid = next(i for i, a in enumerate(self.calls) if a[0] == "exec" and "gen_random_uuid()" in a[-1])
            final_verify = max(i for i, r in enumerate(self.records) if r["worker_operation"] == "verify")
            self.assertLess(inventory, final_verify)
            self.assertLess(final_verify, uuid)
            self.assertTrue(self.records[uuid]["attested_before_command"])
            results = [json.loads(p.read_text()) for p in
                       (self.compose.parent / "build/smoke-deployments").glob("deploy-*/run-result.json")]
            self.assertEqual(sorted(r["run_success"] for r in results), [False, True])
            self.assertEqual(len({r["owner"] for r in results}), 2)
            final = json.loads((shared / "state.json").read_text())
            self.assertTrue(final["containers"]["fake-fe-id"]["State"]["Running"])
            self.assertFalse(any(OWNER in c["Config"].get("Labels", {}) for c in final["containers"].values()))
        finally:
            release.touch(exist_ok=True)
            for child in children:
                if child.poll() is None:
                    os.killpg(child.pid, signal.SIGTERM)
                    try:
                        child.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        os.killpg(child.pid, signal.SIGKILL)
                        child.wait()
                if child.stdout:
                    child.stdout.close()
                if child.stderr:
                    child.stderr.close()

    def test_overlapping_full_invocations_refuse_rival_during_attestation(self):
        self.overlapping_invocations("inventory")

    def test_overlapping_full_invocations_hold_claim_through_smoke(self):
        self.overlapping_invocations("uuid")

    def test_pre_stop_failure_matrix_blocks_full_and_headless_allocation(self):
        for mode in ((), ("--up-only",)):
            for scenario in ("prepare_failure", "snapshot_failure", "stop_failure", "stop_unconfirmed"):
                with self.subTest(mode=mode, scenario=scenario):
                    self.setUp()
                    self.assert_attestation_refused(mode, scenario)
                    self.assertFalse(any(a[:2] == ["volume", "create"] for a in self.calls))
                    self.assertEqual((self.fake_state / "volume/lib/stale.jar").read_bytes(), b"old-provider")

    def test_install_start_failure_matrix_blocks_full_and_headless_allocation(self):
        for mode in ((), ("--up-only",)):
            for scenario in ("install_failure", "worker_exit_status", "hash_failure", "start_failure", "stale_started_at"):
                with self.subTest(mode=mode, scenario=scenario):
                    self.setUp()
                    self.assert_attestation_refused(mode, scenario)
                    self.assert_stopped_write_order()
                    state = json.loads((self.fake_state / "state.json").read_text())
                    self.assertFalse(state["containers"]["fake-fe-id"]["State"]["Running"])

    def test_raw_conflicting_and_unknown_inventory_blocks_both_modes(self):
        for mode in ((), ("--up-only",)):
            for scenario in ("malformed_provider", "conflicting_provider", "unknown_provider_source"):
                with self.subTest(mode=mode, scenario=scenario):
                    self.setUp()
                    self.assert_attestation_refused(mode, scenario)

    def test_runtime_failure_matrix_blocks_full_and_headless_allocation(self):
        for mode in ((), ("--up-only",)):
            for scenario in ("stale_master_be", "stale_matched_pair", "be_registration_timeout", "runtime_select_failure"):
                with self.subTest(mode=mode, scenario=scenario):
                    self.setUp()
                    self.assert_attestation_refused(mode, scenario)

    def test_attestation_receipt_precedes_allocation_and_is_not_a_correctness_claim(self):
        for mode in ((), ("--up-only",)):
            with self.subTest(mode=mode):
                self.setUp()
                self.assertEqual(self.run_driver(*mode).returncode, 0, self.output)
                state, evidence = self.lifecycle()
                receipt = json.loads((evidence / "deployment-attested.json").read_text())
                self.assertEqual(receipt["owner"], state["owner"])
                self.assertEqual(receipt["artifact"], self.artifact)
                self.assertTrue(receipt["loaded_provider_verified"])
                self.assertEqual(receipt["provider_inventory"][0]["EXTENSION_VERSION"], self.artifact["build_id"])
                self.assertEqual(receipt["fe_process"], state["new_process"])
                self.assertEqual(receipt["qualification"], "matching-pinned-fe-be")
                self.assertEqual(receipt["runtime_images"]["fe"]["id"], "fe-image-id")
                self.assertEqual(receipt["runtime_images"]["be"]["id"], "be-image-id")
                self.assertTrue(all(g["passed"] for g in receipt["gates"].values()))
                self.assertFalse(receipt["smoke_correctness_passed"])
                self.assertFalse(receipt["f25_complete"])
                self.assertIsNone(receipt["run_success"])
                result = json.loads((evidence / "run-result.json").read_text())
                self.assertTrue(result["run_success"])
                self.assertFalse(result["smoke_correctness_passed"])
                for _, argv in self.allocation_calls():
                    record = self.records[self.calls.index(argv)]
                    self.assertTrue(record["attested_before_command"])
                self.assertEqual(bool(self.allocation_calls()), not bool(mode))
                for secret in ("minioadmin", "metadata.password", "PG_PASSWORD", "DO-NOT-RECORD"):
                    self.assertNotIn(secret, (evidence / "deployment-attested.json").read_text())

    def assert_attestation_refused(self, mode, scenario, be_image=None):
        self.assertNotEqual(self.run_driver(*mode, scenario=scenario, be_image=be_image).returncode, 0, self.output)
        self.assertEqual(self.allocation_calls(), [])
        self.assert_no_catalog_mutation()
        state, evidence = self.lifecycle()
        self.assertFalse((evidence / "deployment-attested.json").exists())
        self.assertFalse(json.loads((evidence / "run-result.json").read_text())["run_success"])
        return state, evidence

    def test_missing_null_duplicate_builtin_wrong_kind_or_sql_error_block_both_modes(self):
        for mode in ((), ("--up-only",)):
            for scenario in ("missing_provider", "null_provider", "duplicate_provider", "builtin_provider",
                             "wrong_provider_type", "inventory_error"):
                with self.subTest(mode=mode, scenario=scenario):
                    self.setUp()
                    state, _ = self.assert_attestation_refused(mode, scenario)
                    self.assertFalse(state["loaded_provider_verified"])
                    self.assertTrue(any(a[0] == "compose" and "up" in a and a[-1] == "doris-be" for a in self.calls))

    def test_stale_same_api_loaded_a_rejects_fully_verified_disk_b_in_both_modes(self):
        for mode in ((), ("--up-only",)):
            with self.subTest(mode=mode):
                self.setUp()
                loaded_a = self.artifact
                selected_b = write_plugin(self.plugin, payload_value=b"compiled-B-at-same-release-and-api")
                self.assertEqual(loaded_a["api_version"], selected_b["api_version"])
                self.assertNotEqual(loaded_a["build_id"], selected_b["build_id"])
                state, _ = self.assert_attestation_refused(mode, "stale_provider")
                self.assertEqual(state["artifact"], selected_b)
                self.assertEqual(state["provider_inventory"][0]["EXTENSION_VERSION"], loaded_a["build_id"])
                actual = plugin_volume.inventory(self.fake_state / "volume")
                self.assertEqual({k: v["sha256"] for k, v in actual["files"].items()}, selected_b["files"])
                self.assertIn("Loaded DuckLake build identity", self.output)

    def test_changed_process_disk_runtime_or_foreign_backend_fails_before_allocation(self):
        for scenario in ("process_changed_after_inventory", "disk_changed_after_inventory", "foreign_be",
                         "be_registration_timeout", "bad_source_headers", "runtime_select_failure"):
            with self.subTest(scenario=scenario):
                self.setUp()
                self.assert_attestation_refused(("--up-only",), scenario)
                if scenario == "foreign_be":
                    self.assertFalse(any(a[0] == "stop" and a[-1] == "fake-be-id" for a in self.calls))
                    self.assertFalse(any(a[0] == "compose" and "up" in a and a[-1] == "doris-be" for a in self.calls))

    def test_stock_be_experiment_still_requires_provider_and_exact_fe_source(self):
        self.assertEqual(self.run_driver("--up-only", be_image="apache/doris:be-4.1.4").returncode, 0, self.output)
        _, evidence = self.lifecycle()
        receipt = json.loads((evidence / "deployment-attested.json").read_text())
        self.assertEqual(receipt["qualification"], "stock-be-compatibility-experiment")
        self.assertTrue(receipt["gates"]["fe_source_exact"]["passed"])
        self.assertFalse(receipt["gates"]["be_source_exact"]["required"])
        self.assertIsNone(receipt["gates"]["be_source_exact"]["passed"])
        for scenario in ("missing_provider", "stale_matched_pair"):
            with self.subTest(scenario=scenario):
                self.setUp()
                self.assert_attestation_refused(("--up-only",), scenario, be_image="apache/doris:be-4.1.4")

    def test_running_and_stopped_fe_cross_stopped_write_and_fresh_start_boundaries(self):
        for scenario in ("running_fe", "stopped_fe"):
            # Each subcase gets its own private checkout/evidence/daemon.
            with self.subTest(scenario=scenario):
                if scenario == "stopped_fe":
                    self.setUp()
                self.assertEqual(self.run_driver("--up-only", scenario=scenario).returncode, 0, self.output)
                lifecycle, evidence = self.lifecycle()
                events = [e["event"] for e in lifecycle["events"] if e["event"] not in {"worker-created", "worker-finished"}]
                ordered = ["claim-acquired", "owned-target-revalidated", "candidate-prepared", "rollback-preserved",
                           "fe-stopped", "install-started", "installed-files-verified", "fe-start-requested",
                           "fresh-fe-sql-ready", "lifecycle-complete-not-provider-attested", "loaded-provider-identity-matched",
                           "deployment-attested", "smoke-command-finished",
                           "claim-released"]
                self.assertEqual(events, ordered)
                self.assertEqual(lifecycle["prior_process"]["id"], lifecycle["new_process"]["id"])
                self.assertGreater(lifecycle["new_process"]["started_at"], lifecycle["prior_process"]["started_at"])
                self.assertTrue(lifecycle["loaded_provider_verified"])
                previous = json.loads((evidence / "previous.json").read_text())
                self.assertEqual(set(previous["files"]), {"lib/stale.jar", ".old-manifest"})
                stop = next(i for i, a in enumerate(self.calls) if a[0] == "stop" and a[-1] == "fake-fe-id")
                writes = next(i for i, a in enumerate(self.calls) if a[:2] == ["volume", "create"])
                self.assertLess(stop, writes)
                self.assertEqual(self.allocation_calls(), [])

    def test_pre_stop_failures_never_write_the_active_volume_or_stop_a_foreign_fe(self):
        for scenario in ("claim_occupied", "wrong_existing_image", "prepare_failure", "snapshot_failure",
                         "foreign_after_claim", "stop_failure", "stop_unconfirmed"):
            with self.subTest(scenario=scenario):
                self.setUp()
                self.assertNotEqual(self.run_driver("--up-only", scenario=scenario).returncode, 0, self.output)
                self.assertFalse(any(a[:2] == ["volume", "create"] for a in self.calls))
                self.assertEqual(self.allocation_calls(), [])
                self.assertEqual((self.fake_state / "volume/lib/stale.jar").read_bytes(), b"old-provider")
                if scenario in {"claim_occupied", "wrong_existing_image", "prepare_failure", "snapshot_failure", "foreign_after_claim"}:
                    self.assertFalse(any(a[0] == "stop" and a[-1] == "fake-fe-id" for a in self.calls))

    def test_install_hash_start_freshness_and_readiness_failures_keep_fe_stopped_and_backup(self):
        for scenario in ("install_failure", "hash_failure", "changed_old_volume", "start_failure", "stale_started_at",
                         "sql_timeout", "cluster_id_changed"):
            with self.subTest(scenario=scenario):
                self.setUp()
                self.assertNotEqual(self.run_driver("--up-only", scenario=scenario).returncode, 0, self.output)
                self.assertEqual(self.allocation_calls(), [])
                state = json.loads((self.fake_state / "state.json").read_text())
                self.assertFalse(state["containers"]["fake-fe-id"]["State"]["Running"])
                lifecycle, evidence = self.lifecycle()
                self.assertFalse(lifecycle.get("lifecycle_complete", False))
                self.assertTrue((evidence / "previous.tar").is_file())
                self.assertTrue((evidence / "plugin.zip").is_file())
                self.assertIn("failed-fe-kept-stopped", [e["event"] for e in lifecycle["events"]])
                self.assertEqual(list(state["containers"]), ["fake-fe-id"])

    def test_stale_master_be_fails_before_lake_allocation(self):
        self.assertNotEqual(self.run_driver(scenario="stale_master_be").returncode, 0)
        self.assertIn("FE/BE source mismatch", self.output)
        self.assertEqual(self.allocation_calls(), [])
        self.assert_no_catalog_mutation()

    def test_matching_but_stale_pair_fails_before_lake_allocation(self):
        self.assertNotEqual(self.run_driver(scenario="stale_matched_pair").returncode, 0)
        self.assertIn("Unexpected FE/BE source", self.output)
        self.assertEqual(self.allocation_calls(), [])
        self.assert_no_catalog_mutation()

    def test_explicit_stock_compatibility_lane_keeps_local_shuffle_shim(self):
        self.assertEqual(
            self.run_driver("--up-only", be_image="apache/doris:be-4.1.4").returncode,
            0,
            self.output,
        )
        self.assertTrue(any("enable_local_shuffle_planner" in sql for sql in self.mysql_sql()))

    def test_down_only_stops_doris(self):
        self.assertEqual(self.run_driver("--down").returncode, 0, self.output)
        self.assertEqual([a for a in self.calls if "down" in a], [
            ["compose", "-f", str(self.compose / "docker-compose.yml"), "down", "-v"]])
        self.assertEqual(self.allocation_calls(), [])
        self.assertTrue(any(a[0] == "create" and "--name" in a for a in self.calls))

    def assert_rejected_at(self, scenario, stage, token=TOKEN):
        self.assertNotEqual(self.run_driver(scenario=scenario, token=token).returncode, 0, self.output)
        stages = self.allocation_calls()
        expected = ["uuid", "bucket", "prefix", "database", "bootstrap", "path"]
        self.assertEqual([name for name, _ in stages], expected[:expected.index(stage) + 1])
        # Every command after allocation starts must be a preflight command, and
        # the failed preflight must be the last Docker call (no best-effort reuse).
        start = self.calls.index(stages[0][1])
        last = self.calls.index(stages[-1][1])
        self.assertEqual(self.calls[start:last + 1], [argv for _, argv in stages])
        self.assertTrue(all(a[0] in {"stop", "rm"} or a[:2] == ["container", "inspect"]
                            for a in self.calls[last + 1:]))
        self.assert_no_catalog_mutation()

    def test_duplicate_database_stops_before_bootstrap(self):
        self.assert_rejected_at("duplicate_database", "database")

    def test_prefix_listing_error_stops_before_database_creation(self):
        self.assert_rejected_at("prefix_error", "prefix")

    def test_occupied_prefix_stops_before_database_creation(self):
        self.assert_rejected_at("occupied_prefix", "prefix")

    def test_bootstrap_failure_stops_before_metadata_or_catalog_use(self):
        self.assert_rejected_at("bootstrap_failure", "bootstrap")

    def test_wrong_persisted_path_stops_before_catalog_or_helper_mutations(self):
        self.assert_rejected_at("wrong_path", "path")

    def test_malformed_token_stops_before_storage_or_database_allocation(self):
        for token in ("", TOKEN[:-1], TOKEN + "0", TOKEN.upper(), "g" * 32,
                      "01234567-89ab-cdef-0123-456789abcdef", "../" + TOKEN,
                      TOKEN + "'", TOKEN + "\nextra"):
            with self.subTest(token=token):
                self.assert_rejected_at("ok", "uuid", token=token)


if __name__ == "__main__":
    if sys.argv[1:2] == ["--fake-docker"]:
        sys.exit(fake_docker())
    unittest.main()
