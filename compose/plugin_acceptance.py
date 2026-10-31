"""Opt-in F24-E real Doris A/B acceptance on new, UUID-owned infrastructure.

Copies the unmodified public driver into a private compose sandbox. Never targets
the retained default, qualification, R07 or DuckBridge stacks. Requires local
pinned images, two verified bundles and the released DuckDB 1.5.6 CLI.
"""

import argparse
from decimal import Decimal
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import uuid

from plugin_deploy import claim_name, process_record
from plugin_preflight import file_hash, require, verify_bundle
from plugin_volume import verify_backup


FE = "doris-fe:ducklake-62e3d81a-api24"
BE = "doris-be:ducklake-62e3d81a-api24-asan"
IMAGE_PINS = {
    FE: "sha256:f41c6a1710c66f0d0ab0334d5431af85c2002e8399de09ae10b8559bccb8c22a",
    BE: "sha256:ccca761cae5bc1e6122b4449c21d01117d74a095ce203cf9f4d27094ede8a2de",
}
SCRIPTS = ("smoke.sh", "smoke_run.sh", "plugin_deploy.py", "plugin_attest.py", "plugin_preflight.py",
           "plugin_volume.py", "fe.conf", "smoke_lake.py", "step7-delete.py", "step-default.py", "w2-insert.py",
           "smoke_checks.py", "smoke_targets.py", "smoke_rows.py")


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


class Acceptance:
    def __init__(self, bundle_a, bundle_b, duckdb, evidence_root):
        self.source = Path(__file__).resolve().parent
        self.root = Path(tempfile.mkdtemp(prefix="f24-e-", dir=evidence_root)).resolve()
        self.token = uuid.uuid4().hex[:16]
        self.project = "f24-e-" + self.token
        self.doris_project, self.substrate_project = self.project + "-doris", self.project + "-substrate"
        self.fe, self.be = self.project + "-fe", self.project + "-be"
        self.pg, self.minio = self.project + "-pg", self.project + "-minio"
        self.plugin_volume = self.project + "-plugin"
        self.docker = str(Path(shutil.which("docker")).resolve())
        self.duckdb = Path(duckdb).resolve(strict=True)
        self.compose = self.root / "doris-ducklake/compose"
        self.substrate = self.root / "trino-ducklake/compose"
        self.compose.mkdir(parents=True)
        self.substrate.mkdir(parents=True)
        self.warehouse = self.root / "warehouse"
        self.warehouse.mkdir()
        self.database = "f24_e_" + self.token
        self.catalog = "f24_e_" + self.token
        self.password = uuid.uuid4().hex
        self.result = {"schema": "doris-plugin-live-acceptance-v1", "project": self.project,
                       "status": "running", "real_doris": True, "f25_complete": False, "phases": {},
                       "acceptance_runner_sha256": file_hash(Path(__file__)),
                       "acceptance_guard_sha256": file_hash(self.source / "plugin_acceptance_docker.py")}
        self.artifacts, self.bundles = {}, {}
        inputs = self.root / "inputs"
        inputs.mkdir()
        for key, original in (("A", bundle_a), ("B", bundle_b)):
            original = Path(original).resolve(strict=True)
            selected = inputs / (key + ".zip")
            shutil.copyfile(original, selected)
            shutil.copyfile(original.with_suffix(".identity.json"), selected.with_suffix(".identity.json"))
            self.artifacts[key] = verify_bundle(selected, selected.with_suffix(".identity.json"))
            self.bundles[key] = selected
        a, b = self.artifacts["A"], self.artifacts["B"]
        require(a["build_id"] != b["build_id"] and a["api_version"] == b["api_version"] == "24.0", "A/B identity/API mismatch")
        require(a["provider_jar"] == b["provider_jar"] == "lib/doris-ducklake-0.0.1.jar", "A/B must share release version")
        require(a["compile_sdk"] == b["compile_sdk"] and a["compile_sdk_id"] == b["compile_sdk_id"], "A/B SDK differs")
        require({k: v for k, v in a["files"].items() if k != a["provider_jar"]} ==
                {k: v for k, v in b["files"].items() if k != b["provider_jar"]}, "A/B runtime dependencies differ")
        self.result["artifacts"] = self.artifacts
        self.result["copied_tooling_sha256"] = {}
        for name in SCRIPTS:
            shutil.copyfile(self.source / name, self.compose / name)
            self.result["copied_tooling_sha256"][name] = file_hash(self.source / name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        controller = self.root / "controller-sources"
        controller.mkdir()
        shutil.copyfile(Path(__file__), controller / "plugin_acceptance.py")
        shutil.copyfile(self.source / "plugin_acceptance_docker.py", controller / "plugin_acceptance_docker.py")
        shim = self.bin / "docker"
        shim.write_text("#!/usr/bin/env python3\nimport runpy\nrunpy.run_path(" +
                        repr(str(controller / "plugin_acceptance_docker.py")) + ", run_name='__main__')\n")
        shim.chmod(0o755)
        self.doris_file = self.compose / "docker-compose.yml"
        self.substrate_file = self.substrate / "docker-compose.yml"
        self.started = False

    def command(self, args, **kwargs):
        return subprocess.run([self.docker, *map(str, args)], check=True, text=True, capture_output=True, timeout=300, **kwargs).stdout.rstrip("\r\n")

    def data(self, *args):
        return json.loads(self.command(args))

    def protected(self):
        names = self.command(["container", "ls", "--all", "--format", "{{.Names}}"])
        selected = [n for n in names.splitlines() if n.startswith(("doris-api24-", "doris-r07-", "doris-ducklake-", "trino-ducklake-"))]
        containers = {}
        for name in selected:
            item = self.data("container", "inspect", name)[0]
            containers[name] = {"id": item["Id"], "image": item["Image"], "running": item["State"]["Running"],
                                "started_at": item["State"]["StartedAt"]}
        files = {}
        for path in (self.source / ".fe.conf.runtime", self.source.parent / "build/distributions/doris-ducklake-0.0.1-plugin.zip",
                     self.source.parent / "dev-docs/VALIDATION-QUACK-JDBC-0.11.0-2026-10-29.md"):
            files[str(path)] = file_hash(path) if path.is_file() else None
        return {"containers": containers, "files": files}

    def prepare(self):
        self.result["protected_before"] = self.protected()
        images = {}
        for reference in (FE, BE, "python:3.12-slim", "postgres:18-alpine", "minio/minio:RELEASE.2025-01-20T14-49-07Z"):
            images[reference] = self.data("image", "inspect", reference)[0]["Id"]
        require(all(images[k] == value for k, value in IMAGE_PINS.items()), "Pinned Doris image tag drift")
        self.result["images"] = images
        version = subprocess.check_output([str(self.duckdb), "--version"], text=True).strip()
        require(version.startswith("v1.5.6 "), "Acceptance requires the released 1.5.6 producer, not 2.x/catalog migration")
        self.result["producer"] = {"version": version, "sha256": file_hash(self.duckdb)}
        networks = self.command(["network", "ls", "--format", "{{.ID}}"])
        occupied = []
        for identifier in networks.splitlines():
            for config in self.data("network", "inspect", identifier)[0].get("IPAM", {}).get("Config") or []:
                if config.get("Subnet"):
                    occupied.append(ipaddress.ip_network(config["Subnet"]))
        subnet = next(ipaddress.ip_network(f"198.18.{i}.0/24") for i in range(16, 240)
                      if all(not ipaddress.ip_network(f"198.18.{i}.0/24").overlaps(n) for n in occupied if n.version == 4))
        fe_ip, be_ip = str(subnet.network_address + 10), str(subnet.network_address + 20)
        self.result["subnet"] = str(subnet)
        runtime = (self.source / "fe.conf").read_text() + f"\npriority_networks = {subnet}\n"
        (self.compose / ".fe.conf.runtime").write_text(runtime)
        network = self.project + "-substrate-network"
        volumes = {key: {"name": self.project + "-" + key} for key in ("fe-meta", "fe-log", "be-storage", "be-log")}
        volumes["fe-plugin-ducklake"] = {"name": self.plugin_volume}
        mounts = [f"{self.warehouse}:{self.warehouse}:ro"]
        config = {"name": self.doris_project, "services": {
            "doris-fe": {"image": images[FE], "container_name": self.fe, "hostname": "doris-fe",
                "environment": {"FE_SERVERS": f"fe1:{fe_ip}:9010", "FE_ID": "1"},
                "volumes": ["./.fe.conf.runtime:/opt/apache-doris/fe/conf/fe.conf",
                            "fe-plugin-ducklake:/opt/apache-doris/fe/plugins/connector/ducklake",
                            "fe-meta:/opt/apache-doris/fe/doris-meta", "fe-log:/opt/apache-doris/fe/log", *mounts],
                "networks": {"doris-net": {"ipv4_address": fe_ip}, "substrate": {}},
                "healthcheck": {"test": ["CMD-SHELL", "mysql -h127.0.0.1 -P9030 -uroot -e 'SHOW FRONTENDS' >/dev/null 2>&1"],
                                "interval": "5s", "timeout": "5s", "retries": 60, "start_period": "60s"},
                "mem_limit": "5g", "cpus": 3},
            "doris-be": {"image": images[BE], "container_name": self.be, "hostname": "doris-be", "platform": "linux/amd64",
                "environment": {"FE_SERVERS": f"fe1:{fe_ip}:9020", "BE_ADDR": f"{be_ip}:9050"},
                "volumes": ["be-storage:/opt/apache-doris/be/storage", "be-log:/opt/apache-doris/be/log", *mounts],
                "networks": {"doris-net": {"ipv4_address": be_ip}, "substrate": {}},
                "ulimits": {"nofile": {"soft": 65536, "hard": 65536}}, "mem_limit": "12g", "cpus": 6}},
            "networks": {"doris-net": {"name": self.project + "-doris-network", "ipam": {"config": [{"subnet": str(subnet)}]}},
                         "substrate": {"external": True, "name": network}}, "volumes": volumes}
        substrate = {"name": self.substrate_project, "services": {
            "postgres": {"image": images["postgres:18-alpine"], "container_name": self.pg,
                "environment": {"POSTGRES_USER": "ducklake", "POSTGRES_PASSWORD": self.password, "POSTGRES_DB": self.database},
                "ports": ["127.0.0.1::5432"], "volumes": ["pg-data:/var/lib/postgresql"],
                "healthcheck": {"test": ["CMD-SHELL", f"pg_isready -U ducklake -d {self.database}"],
                                "interval": "2s", "timeout": "3s", "retries": 60}},
            "minio": {"image": images["minio/minio:RELEASE.2025-01-20T14-49-07Z"], "container_name": self.minio,
                "command": ["server", "/data"], "volumes": ["minio-data:/data"],
                "environment": {"MINIO_ROOT_USER": "f24-fixture", "MINIO_ROOT_PASSWORD": self.password}}},
            "networks": {"default": {"name": network}},
            "volumes": {"pg-data": {"name": self.project + "-pg-data"}, "minio-data": {"name": self.project + "-minio-data"}}}
        write_json(self.doris_file, config)
        write_json(self.substrate_file, substrate)
        # UUIDs do not themselves grant ownership: reject every pre-existing name.
        for kind, names in (("container", (self.fe, self.be, self.pg, self.minio)),
                            ("volume", tuple(v["name"] for v in volumes.values()) + (self.project + "-pg-data", self.project + "-minio-data")),
                            ("network", (network, self.project + "-doris-network"))):
            existing = self.command([kind, "ls", *(["--all"] if kind == "container" else []), "--format", "{{.Names}}" if kind == "container" else "{{.Name}}"])
            require(not set(names).intersection(existing.splitlines()), "Acceptance name collision; no adoption permitted")
        self.result["ownership_names_checked_absent"] = True

    def phase(self, name, bundle, stale=False):
        evidence = self.compose.parent / "build/smoke-deployments"
        before = set(evidence.glob("deploy-*")) if evidence.exists() else set()
        trace = self.root / (name + "-docker.jsonl")
        spec = {"docker": self.docker, "projects": [self.doris_project, self.substrate_project],
                "compose_files": [str(self.doris_file), str(self.substrate_file)], "plugin_volume": self.plugin_volume,
                "claim_name": claim_name(self.plugin_volume), "log": str(trace), "inject_stale": stale,
                "expected_actual": self.artifacts["B"]["build_id"], "stale_version": self.artifacts["A"]["build_id"]}
        spec_path = self.root / (name + "-docker-spec.json")
        write_json(spec_path, spec)
        env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ.get("PATH", ""),
                   F24_ACCEPTANCE_DOCKER=str(spec_path), PYTHONDONTWRITEBYTECODE="1", DORIS_FE_STARTUP_TIMEOUT="300",
                   DORIS_EXPECTED_SOURCE="62e3d81a42b", DORIS_BE_PLATFORM="linux/amd64", DORIS_CORPUS_DIR=str(self.root / "corpus"))
        args = ["bash", str(self.compose / "smoke.sh"), "--plugin-zip", str(self.bundles[bundle])]
        if not stale:
            args.append("--up-only")
        self.started = True
        child = subprocess.Popen(args, env=env, cwd=self.root, text=True, stdout=subprocess.PIPE,
                                 stderr=subprocess.PIPE, start_new_session=True)
        try:
            stdout, stderr = child.communicate(timeout=900)
        except BaseException:
            os.killpg(child.pid, signal.SIGTERM)
            try:
                child.communicate(timeout=100)
            except subprocess.TimeoutExpired:
                os.killpg(child.pid, signal.SIGKILL)
                child.communicate()
            raise
        result = subprocess.CompletedProcess(args, child.returncode, stdout, stderr)
        (self.root / (name + ".log")).write_text(stdout + stderr)
        new = set(evidence.glob("deploy-*")) - before
        require(len(new) == 1, f"Expected one run-owned evidence directory for {name}; see its log")
        directory = new.pop()
        run = json.loads((directory / "run-result.json").read_text())
        events = [json.loads(line) for line in trace.read_text().splitlines()]
        require(not any(e.get("allocation_attempt") or e.get("scope_allowed") is False for e in events),
                "Acceptance attempted an allocation or foreign target")
        phase = {"exit_code": result.returncode, "evidence": str(directory), "run_result": run,
                 "allocation_attempts": 0, "docker_commands": len(events)}
        self.result["phases"][name] = phase
        if stale:
            require(result.returncode != 0 and not run["run_success"] and "Loaded DuckLake build identity" in result.stderr,
                    "Injected stale identity did not fail at the provider gate")
            require(not (directory / "deployment-attested.json").exists(), "Passing receipt survived stale-identity failure")
            injections = [e for e in events if e.get("injected_stale")]
            require(len(injections) == 1, "Missing exact real-row stale-inventory injection")
            require(not self.data("container", "inspect", self.fe)[0]["State"]["Running"], "Failed FE was not kept stopped")
            phase["injection"] = injections[0]
        else:
            require(result.returncode == 0 and run["run_success"], f"Deployment {name} failed; see {self.root / (name + '.log')}")
            receipt = json.loads((directory / "deployment-attested.json").read_text())
            require(receipt["artifact"] == self.artifacts[bundle] and receipt["loaded_provider_verified"], "Wrong attested bundle")
            require(receipt["provider_inventory"] == [{"EXTENSION_NAME": "ducklake", "EXTENSION_TYPE": "CONNECTOR",
                    "EXTENSION_VERSION": self.artifacts[bundle]["build_id"], "SOURCE": "EXTERNAL"}], "Wrong live provider inventory")
            require(not receipt["smoke_correctness_passed"] and not receipt["f25_complete"], "Overclaimed F25 correctness")
            require(self.password not in (directory / "deployment-attested.json").read_text(), "Credential in receipt")
            phase["attestation"] = receipt
        write_json(self.root / "acceptance.json", self.result)
        return phase

    def query(self, sql, headers=False):
        flags = ["-B", "-r"] + ([] if headers else ["-N"])
        return self.command(["exec", "-i", self.fe, "mysql", "-h127.0.0.1", "-P9030", "-uroot", *flags], input=sql + ";\n")

    def identity(self):
        version = self.command(["exec", self.fe, "cat", "/opt/apache-doris/fe/doris-meta/image/VERSION"])
        cluster = next(line.partition("=")[2].strip() for line in version.splitlines() if line.partition("=")[0] == "clusterId")
        rows = self.query("SHOW CATALOGS", headers=True).splitlines()
        columns = rows[0].split("\t")
        selected = [dict(zip(columns, row.split("\t"))) for row in rows[1:] if self.catalog in row.split("\t")]
        require(len(selected) == 1, "Persisted acceptance catalog missing or ambiguous")
        be = self.data("container", "inspect", self.be)[0]
        return {"cluster_id": cluster, "catalog": {k: selected[0][k] for k in ("CatalogId", "CatalogName")},
                "fe": process_record(self.data("container", "inspect", self.fe)[0]),
                "be": {"id": be["Id"], "image": be["Image"], "started_at": be["State"]["StartedAt"],
                       "running": be["State"]["Running"]}}

    def seed(self):
        pg = self.data("container", "inspect", self.pg)[0]
        port = pg["NetworkSettings"]["Ports"]["5432/tcp"][0]["HostPort"]
        extensions = self.root / "producer-extensions"
        extensions.mkdir()
        sql = (f"SET extension_directory='{extensions}'; INSTALL ducklake; LOAD ducklake; INSTALL postgres; LOAD postgres; "
               f"ATTACH 'ducklake:postgres:dbname={self.database} host=127.0.0.1 port={port} user=ducklake password={self.password}' "
               f"AS dl (DATA_PATH '{self.warehouse}'); CALL dl.set_option('data_inlining_row_limit','0'); "
               "CREATE TABLE dl.main.provider_probe(id BIGINT, label VARCHAR, amount DECIMAL(9,2)); "
               "INSERT INTO dl.main.provider_probe VALUES (1,'alpha',10.25),(2,NULL,-3.50),(3,'quote''d',NULL); CHECKPOINT dl; "
               "SELECT id,label,amount FROM dl.main.provider_probe ORDER BY id;")
        env = dict(os.environ)
        env.pop("AI_AGENT", None)
        env["HOME"] = str(self.root)
        result = subprocess.run([str(self.duckdb), "-batch", "-bail", "-init", "/dev/null", ":memory:"],
                                input=sql, env=env, text=True, capture_output=True, timeout=240)
        (self.root / "producer.log").write_text(result.stdout + result.stderr)
        require(result.returncode == 0, "Released DuckDB producer failed; inspect private producer log")
        require(any(self.warehouse.rglob("*.parquet")), "Producer did not create a native file-backed fixture")
        self.query(f"CREATE CATALOG `{self.catalog}` PROPERTIES ('type'='ducklake',"
                   f"'metadata.url'='jdbc:postgresql://{self.pg}:5432/{self.database}',"
                   f"'metadata.user'='ducklake','metadata.password'='{self.password}','storage.warehouse'='{self.warehouse}')")

    def exact_read(self):
        raw = self.query(f"SELECT id,label,amount FROM `{self.catalog}`.main.provider_probe ORDER BY id")
        rows = [[int(fields[0]), None if fields[1] == "NULL" else fields[1],
                 None if fields[2] == "NULL" else str(Decimal(fields[2]))]
                for fields in (line.split("\t") for line in raw.splitlines())]
        require(rows == [[1, "alpha", "10.25"], [2, None, "-3.50"], [3, "quote'd", None]], f"Exact typed read mismatch: {rows}")
        return rows

    def cleanup(self):
        if not self.started:
            return
        claims = self.command(["container", "ls", "--all", "--filter", f"name=^/{claim_name(self.plugin_volume)}$", "--format", "{{.ID}}"])
        require(not claims, "Uncertain retained claim: preserve resources for deliberate recovery, do not auto-steal")
        for name in (self.fe, self.be):
            result = subprocess.run([self.docker, "logs", "--tail", "150", name], text=True, capture_output=True)
            (self.root / (name + "-container.log")).write_text(result.stdout + result.stderr)
        for file in (self.doris_file, self.substrate_file):
            result = subprocess.run([self.docker, "compose", "-f", str(file), "down", "-v"], text=True, capture_output=True, timeout=180)
            (self.root / (file.parent.parent.name + "-cleanup.log")).write_text(result.stdout + result.stderr)
            require(result.returncode == 0, "Owned acceptance cleanup failed")
        for project in (self.doris_project, self.substrate_project):
            for kind in ("container", "volume", "network"):
                args = [kind, "ls", *(["--all"] if kind == "container" else []), "--filter",
                        f"label=com.docker.compose.project={project}", "--format", "{{.ID}}" if kind != "volume" else "{{.Name}}"]
                require(not self.command(args), "Owned acceptance resources remain after cleanup")
        self.result["owned_resources_cleaned"] = True

    def run(self):
        print(f"F24-E evidence: {self.root}", flush=True)
        try:
            self.prepare()
            a = self.phase("A", "A")
            self.seed()
            before = self.identity()
            self.result["identity_before_replacement"] = before
            self.result["read_A"] = self.exact_read()
            warehouse = {str(p.relative_to(self.warehouse)): file_hash(p) for p in self.warehouse.rglob("*") if p.is_file()}
            b = self.phase("B", "B")
            after = self.identity()
            require(before["cluster_id"] == after["cluster_id"] and before["catalog"] == after["catalog"] and
                    before["fe"]["id"] == after["fe"]["id"] and before["be"] == after["be"], "Metadata/container identity was reset")
            require(after["fe"]["started_at"] > before["fe"]["started_at"], "B did not start a fresh FE")
            self.result["identity_after_replacement"] = after
            self.result["read_B"] = self.exact_read()
            backup_dir = Path(b["evidence"])
            backup = json.loads((backup_dir / "previous.json").read_text())
            verify_backup(backup_dir / "previous.tar", backup)
            require({k: v["sha256"] for k, v in backup["files"].items()} == self.artifacts["A"]["files"], "B rollback is not complete A")
            self.phase("stale-full-run", "B", stale=True)
            self.phase("recovered-B", "B")
            recovered = self.identity()
            require(before["cluster_id"] == recovered["cluster_id"] and before["catalog"] == recovered["catalog"] and
                    before["be"] == recovered["be"] and before["fe"]["id"] == recovered["fe"]["id"], "Recovery reset metadata/process identity")
            self.result["read_recovered_B"] = self.exact_read()
            require(warehouse == {str(p.relative_to(self.warehouse)): file_hash(p) for p in self.warehouse.rglob("*") if p.is_file()},
                    "Acceptance read/replacement changed the warehouse")
            require(all(file_hash(self.source / name) == value for name, value in self.result["copied_tooling_sha256"].items()) and
                    file_hash(self.source / "plugin_acceptance_docker.py") == self.result["acceptance_guard_sha256"],
                    "Acceptance tooling changed during the live run")
            self.result.update(status="passed", real_loaded_A=True, real_loaded_B=True,
                               injected_stale_refused_before_allocation=True, metadata_preserved=True,
                               rollback_A_verified=True, warehouse_unchanged=True)
        except BaseException as error:
            self.result.update(status="failed", error=str(error))
            raise
        finally:
            try:
                self.cleanup()
                if "protected_before" in self.result:
                    self.result["protected_after"] = self.protected()
                    require(self.result["protected_before"] == self.result["protected_after"], "Retained stack/artifact/report changed")
            except BaseException as error:
                self.result.update(status="failed", cleanup_or_protection_error=str(error))
                raise
            finally:
                write_json(self.root / "acceptance.json", self.result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle-a", type=Path, required=True)
    parser.add_argument("--bundle-b", type=Path, required=True)
    parser.add_argument("--duckdb", type=Path, required=True)
    parser.add_argument("--evidence-root", type=Path, default=Path("/tmp/opencode"))
    args = parser.parse_args()
    acceptance = None
    try:
        acceptance = Acceptance(args.bundle_a, args.bundle_b, args.duckdb, args.evidence_root)
        acceptance.run()
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.SubprocessError) as error:
        location = acceptance.root if acceptance is not None else args.evidence_root
        print(f"F24-E failed: {error}; evidence retained under {location}", file=sys.stderr)
        return 1
    print(f"PASS F24-E real loaded A/B and injected refusal; evidence: {acceptance.root}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
