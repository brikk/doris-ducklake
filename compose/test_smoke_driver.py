"""F21 shell orchestration regressions, with no Docker or database access.

The Docker stand-in records argv and returns canned success/count responses. It
does not execute container payloads or model SQL, storage, or engine semantics;
assertions inspect the commands the real smoke.sh constructs.
"""

import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile


TOKEN = "0123456789abcdef0123456789abcdef"
DATABASE = f"doris_smoke_{TOKEN}"
PREFIX = f"doris-smoke/{TOKEN}/"
DATA_PATH = f"s3://ducklake/{PREFIX}"
MC_ROOT = f"m/ducklake/{PREFIX}"
SOURCE = Path(__file__).resolve().parent


def option_values(argv, flag):
    return [argv[i + 1] for i, arg in enumerate(argv[:-1]) if arg == flag]


def fake_docker():
    argv = sys.argv[2:]
    log = Path(os.environ["FAKE_DOCKER_LOG"])
    previous = [json.loads(line)["argv"] for line in log.read_text().splitlines()]
    with log.open("a") as stream:
        stream.write(json.dumps({
            "argv": argv, "duckdb_version": os.environ.get("DUCKDB_VERSION"),
        }) + "\n")
    scenario = os.environ["FAKE_SCENARIO"]

    if argv[0] == "compose":
        if "run" in argv and scenario == "bootstrap_failure":
            print("injected bootstrap failure", file=sys.stderr)
            return 1
        return 0
    if argv[0] in {"volume", "cp", "start", "rm"}:
        return 0
    if argv[0] == "create":
        print("fake-plugin-helper")
        return 0
    if argv[:3] == ["exec", "trino-ducklake-postgres", "psql"]:
        sql = " ".join(option_values(argv, "-c")[0].split())
        if "gen_random_uuid()" in sql:
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
    if argv[:3] == ["exec", "doris-ducklake-fe", "mysql"]:
        sql = " ".join(option_values(argv, "-e")[0].split())
        if sql == "SHOW BACKENDS":
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
                print(93)
            elif ".default_probe" in sql:
                print(0 if "IS NULL" in sql else 3)
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
                     "step-default.py", "w2-insert.py"):
            shutil.copyfile(SOURCE / name, self.compose / name)
        distributions = self.compose.parent / "build" / "distributions"
        distributions.mkdir(parents=True)
        self.plugin = distributions / "doris-ducklake-test-plugin.zip"
        with zipfile.ZipFile(self.plugin, "w") as archive:
            archive.writestr("plugin.properties", "name=ducklake\n")
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

    def run_driver(self, *args, scenario="ok", token=TOKEN):
        self.log.write_text("")
        # Do not inherit BASH_ENV, exported functions, Docker settings, etc.
        env = {
            "PATH": str(self.bin) + os.pathsep + os.defpath,
            "HOME": str(self.root), "LC_ALL": "C",
            "PYTHONDONTWRITEBYTECODE": "1",
            "DORIS_CORPUS_DIR": str(self.root / "corpus"),
            "FAKE_DOCKER_LOG": str(self.log), "FAKE_SCENARIO": scenario,
            "FAKE_TOKEN": token,
            # Inherited targets must not let a caller adopt the shared lake.
            "SMOKE_RUN_ID": "f" * 32, "SMOKE_PG_DB": "ducklake",
            "SMOKE_CATALOG": "dl", "SMOKE_PREFIX": "data/",
            "SMOKE_DATA_PATH": "s3://ducklake/data/", "SMOKE_MC_ROOT": "m/ducklake/data/",
            "PG_DB": "ducklake", "DATA_PATH": "s3://ducklake/data/",
            "S3_DATA_PREFIX": "data/", "DUCKDB_VERSION": "must-be-overridden",
        }
        result = subprocess.run(
            ["bash", str(self.compose / "smoke.sh"), "--no-build", *args],
            cwd=self.root, env=env, text=True, capture_output=True, timeout=30,
        )
        records = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.records = records
        self.calls = [record["argv"] for record in records]
        self.output = result.stdout + result.stderr
        self.assertEqual((self.compose / ".fe.conf.runtime").read_bytes(),
                         (self.compose / "fe.conf").read_bytes())
        return result

    def mysql_sql(self):
        return [option_values(argv, "-e")[0] for argv in self.calls
                if argv[:3] == ["exec", "doris-ducklake-fe", "mysql"]]

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
            ["compose", "-f", str(self.compose / "docker-compose.yml"), "up", "-d"],
        ])

    def test_full_run_owns_all_targets(self):
        self.assertEqual(self.run_driver().returncode, 0, self.output)
        self.assert_infrastructure_only_up()
        stages = self.allocation_calls()
        self.assertEqual([name for name, _ in stages],
                         ["uuid", "bucket", "prefix", "database", "bootstrap", "path"])
        stage_indexes = [self.calls.index(argv) for _, argv in stages]
        self.assertEqual(stage_indexes, sorted(stage_indexes))
        self.assertIn(["cp", str(self.plugin), "fake-plugin-helper:/tmp/plugin.zip"], self.calls)

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
        self.assertEqual(self.records[stage_indexes[4]]["duckdb_version"], "1.5.5")

        helpers = [argv for argv in self.calls if "python:3.12-slim" in argv]
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
                            if argv[:3] == ["exec", "doris-ducklake-fe", "mysql"]
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
            if argv[:3] == ["exec", "doris-ducklake-fe", "mysql"]:
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
        self.assertIn("minio/mc:latest", self.calls[-1])
        self.assertIn(f"mc rm {MC_ROOT}_SUCCESS", self.calls[-1][-1])

    def test_up_only_does_not_seed_or_mutate_catalogs(self):
        self.assertEqual(self.run_driver("--up-only").returncode, 0, self.output)
        self.assert_infrastructure_only_up()
        self.assert_no_catalog_mutation()
        self.assertTrue(any("SHOW BACKENDS" in sql for sql in self.mysql_sql()))
        self.assertEqual(self.allocation_calls(), [])
        self.assertFalse(any(argv[0] == "run" or "psql" in argv for argv in self.calls))

    def test_down_only_stops_doris(self):
        self.assertEqual(self.run_driver("--down").returncode, 0, self.output)
        self.assertEqual(self.calls, [
            ["compose", "-f", str(self.compose / "docker-compose.yml"), "down", "-v"],
        ])

    def assert_rejected_at(self, scenario, stage, token=TOKEN):
        self.assertNotEqual(self.run_driver(scenario=scenario, token=token).returncode, 0, self.output)
        stages = self.allocation_calls()
        expected = ["uuid", "bucket", "prefix", "database", "bootstrap", "path"]
        self.assertEqual([name for name, _ in stages], expected[:expected.index(stage) + 1])
        # Every command after allocation starts must be a preflight command, and
        # the failed preflight must be the last Docker call (no best-effort reuse).
        start = self.calls.index(stages[0][1])
        self.assertEqual(self.calls[start:], [argv for _, argv in stages])
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
