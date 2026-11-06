"""Bounded candidate/default acceptance on owned API27 FE/BE + PostgreSQL/S3."""
import argparse
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import xml.etree.ElementTree as ET

from plugin_acceptance import write_json
from plugin_preflight import file_hash, require
from smoke_acceptance import FullSmokeAcceptance


class ReadCorrectnessAcceptance(FullSmokeAcceptance):
    candidate = True
    phase_name = "candidate-full-supported"
    seed_script = "read_correctness_seed.py"
    output_directory = "catalog-read-correctness-33"
    live_tasks = ("readCorrectnessLiveTest", "readCorrectnessLiveJava17Test")
    live_test_count = 2
    live_marker = "positive mirrors=2/2; wrong-hash=1 failed, later mirror=1/1"

    def source_hashes(self):
        project = self.source.parent
        files = [project / "build.gradle.kts", project / "gradle/libs.versions.toml"]
        for directory in ("src", "read-tiers", "test/src", "test/r07"):
            files.extend((project / directory).rglob("*.kt"))
        return {str(path.relative_to(project)): file_hash(path) for path in sorted(files)}

    def prepare(self):
        super().prepare()
        # Loopback ephemeral ports only, needed by the JDBC oracle and engine test JVMs.
        for file, service, port in ((self.doris_file, "doris-fe", 9030), (self.substrate_file, "minio", 9000)):
            config = json.loads(file.read_text())
            with socket.socket() as reservation:
                reservation.bind(("127.0.0.1", 0))
                published = reservation.getsockname()[1]
            # F23 requires a concrete port in the preflight plan. If another process wins
            # the bind race, Compose fails; never stop/adopt that process or relax the gate.
            config["services"][service]["ports"] = [f"127.0.0.1:{published}:{port}"]
            write_json(file, config)
        for name in (self.seed_script, "read_correctness_acceptance.py"):
            shutil.copy2(self.source / name, self.root / "controller-sources" / name)
            self.result["copied_tooling_sha256"][name] = file_hash(self.source / name)
        self.result["source_sha256"] = self.source_hashes()

    def port(self, container, port):
        binding = self.data("container", "inspect", container)[0]["NetworkSettings"]["Ports"][f"{port}/tcp"]
        require(len(binding) == 1 and binding[0]["HostIp"] == "127.0.0.1", "Unexpected fixture port binding")
        return binding[0]["HostPort"]

    def seed_nested(self):
        run = self.result["phases"][self.phase_name]["correctness"]["run_id"]
        self.fixture_database = "doris_smoke_" + run
        self.fixture_data = f"s3://ducklake/doris-smoke/{run}/"
        env = dict(os.environ, SMOKE_RUN_ID=run, PG_DB=self.fixture_database, DATA_PATH=self.fixture_data,
                   PG_HOST=self.pg, PG_USER="ducklake", PG_PASSWORD=self.password, S3_ENDPOINT=self.minio + ":9000",
                   S3_KEY_ID="f24-fixture", S3_SECRET=self.password)
        args = [self.docker, "run", "--rm", "--pull=never", "--network", self.project + "-substrate-network",
                "-v", str(self.root / "controller-sources" / self.seed_script) + ":/script.py:ro",
                "-v", str(self.compose / "smoke_lake.py") + ":/smoke_lake.py:ro"]
        for key in ("SMOKE_RUN_ID", "PG_DB", "DATA_PATH", "PG_HOST", "PG_USER", "PG_PASSWORD", "S3_ENDPOINT", "S3_KEY_ID", "S3_SECRET"):
            args += ["-e", key]
        args += [self.result["images"]["python:3.12-slim"], "sh", "-ec",
                 'pip install --quiet --no-cache-dir "duckdb==1.5.6" >&2; exec python /script.py']
        result = subprocess.run(args, env=env, capture_output=True, text=True, timeout=600)
        (self.root / "native-seed.log").write_text(result.stderr.replace(self.password, "<redacted>"))
        require(result.returncode == 0, "Native fixture failed; inspect redacted native-seed.log")
        oracle = json.loads(result.stdout)
        write_json(self.root / "native-oracle.json", oracle)
        self.query(f"REFRESH CATALOG {self.fixture_database}")
        self.result["native_fixture"] = oracle
        # Not a public receipt: private owned fixture connection settings, never print credentials.
        properties = {"fe.url": f"jdbc:mysql://127.0.0.1:{self.port(self.fe, 9030)}/?tinyInt1isBit=true",
                      "pg.port": self.port(self.pg, 5432), "minio.host": "127.0.0.1:" + self.port(self.minio, 9000),
                      "catalog": self.fixture_database, "database": self.fixture_database,
                      "data.path": self.fixture_data, "password": self.password}
        self.fixture_file = self.root / "fixture-private.properties"
        self.fixture_file.write_text("".join(f"{k}={v}\n" for k, v in properties.items()))
        self.fixture_file.chmod(0o600)

    def run(self):
        print("Read-correctness evidence:", self.root, flush=True)
        try:
            self.prepare()
            self.full_phase(self.phase_name, bundle="A")
            self.seed_nested()
            project = self.source.parent
            env = dict(os.environ, DORIS_READ_CORRECTNESS_FIXTURE=str(self.fixture_file))
            java_tmp = self.root / "java-tmp"
            java_tmp.mkdir()
            init = self.root / "live-tests.init.gradle"
            init.write_text("gradle.beforeProject { project -> if (project.projectDir == new File(" + json.dumps(str(project)) +
                            ")) project.layout.buildDirectory.set(new File(project.projectDir, " + json.dumps("build/" + self.output_directory) + ")) }\n" +
                            "allprojects { tasks.withType(Test).configureEach { maxHeapSize = '768m'; maxParallelForks = 1; " +
                            "systemProperty 'java.io.tmpdir', " + json.dumps(str(java_tmp)) + " } }\n")
            command = [str(project / "gradlew"), "--project-cache-dir", str(self.root / "gradle-project-cache"), "-I", str(init),
                       "-PcatalogReadCorrectnessCandidate=" + str(self.candidate).lower(), *self.live_tasks, "--continue",
                       "--no-daemon", "--max-workers=1", "-Pkotlin.compiler.execution.strategy=in-process",
                       "-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m -Djava.io.tmpdir=" + str(java_tmp)]
            with (self.root / "live-jvm.log").open("w") as log:
                result = subprocess.run(command, cwd=project, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=1200)
            log_path = self.root / "live-jvm.log"
            log_path.write_text(log_path.read_text().replace(self.password, "<redacted>"))
            self.result["live_jvm_exit"] = result.returncode
            require(result.returncode == 0, "Live nested/replay JVM checks failed; inspect live-jvm.log")
            self.result["live_tests"] = {}
            for task in self.live_tasks:
                files = list((project / "build" / self.output_directory / "test-results" / task).glob("TEST-*.xml"))
                require(len(files) == 1, "Missing bounded live test XML")
                suite = ET.parse(files[0]).getroot()
                counts = {key: int(suite.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}
                require(counts == {"tests": self.live_test_count, "failures": 0, "errors": 0, "skipped": 0}, "Incomplete live tests")
                require(self.live_marker in files[0].read_text(), "Missing live read evidence")
                require(self.password not in files[0].read_text(), "Credential in live test receipt")
                shutil.copy2(files[0], self.root / (task + ".xml"))
                self.result["live_tests"][task] = counts
            require(all(file_hash(self.source / name) == digest for name, digest in self.result["copied_tooling_sha256"].items()),
                    "Tooling changed during live acceptance")
            require(self.source_hashes() == self.result["source_sha256"], "Connector/test source changed during live acceptance")
            self.result["status"] = "passed"
        except BaseException as error:
            self.result.update(status="failed", error=str(error).replace(self.password, "<redacted>"))
            raise
        finally:
            try:
                self.cleanup()
                if "protected_before" in self.result:
                    self.result["protected_after"] = self.protected()
                    require(self.result["protected_before"] == self.result["protected_after"], "Protected state changed")
            except BaseException as error:
                self.result.update(status="failed", cleanup_error=str(error).replace(self.password, "<redacted>"))
                raise
            finally:
                write_json(self.root / "acceptance.json", self.result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("bundle-a", "bundle-b", "duckdb", "evidence-root"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--released", action="store_true", help="Run catalog0.13 release nested/replay probes, not the candidate")
    args = parser.parse_args()
    kind = DefaultNestedAcceptance if args.released else ReadCorrectnessAcceptance
    fixture = kind(args.bundle_a, args.bundle_b, args.duckdb, args.evidence_root)
    try:
        fixture.run()
    except Exception as error:
        print("Read-correctness acceptance failed:", str(error).replace(fixture.password, "<redacted>"))
        return 1
    print("PASS bounded API27 " + ("released native-file/replay" if args.released else "candidate native-file/replay") + " acceptance:", fixture.root)
    return 0


class DefaultNestedAcceptance(ReadCorrectnessAcceptance):
    candidate = False
    phase_name = "released-full-supported"
    output_directory = "default-nested-qualification"

    def prepare(self):
        for key in ("A", "B"):
            catalogs = {name for name in self.artifacts[key]["files"] if name.startswith("lib/ducklake-catalog-")}
            require(catalogs == {"lib/ducklake-catalog-0.17.0.jar"}, "Not a default catalog0.17.0 bundle")
        super().prepare()
        self.result["lane"] = "released-catalog-0.17.0"


if __name__ == "__main__":
    raise SystemExit(main())
