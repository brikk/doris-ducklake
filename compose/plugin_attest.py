"""F24-C: attest load-time registry identity, never a version inferred from disk."""

import json
import os
import re
import time

from plugin_preflight import require, sha256


INVENTORY_COLUMNS = ("EXTENSION_NAME", "EXTENSION_TYPE", "EXTENSION_VERSION", "SOURCE")
INVENTORY_SQL = ("SELECT " + ", ".join(INVENTORY_COLUMNS) +
                 " FROM information_schema.extensions WHERE LOWER(EXTENSION_NAME) = 'ducklake'")
STOCK_BE = "apache/doris:be-4.1.4"


def inventory_rows(text):
    rows = []
    for line in text.splitlines():
        values = line.split("\t")
        require(len(values) == len(INVENTORY_COLUMNS), "Malformed extension inventory row")
        rows.append(dict(zip(INVENTORY_COLUMNS, values)))
    return rows


def validate_inventory(rows, expected):
    require(len(rows) == 1, f"Expected exactly one DuckLake extension row, found {len(rows)}")
    row = rows[0]
    require(row["EXTENSION_NAME"] == "ducklake" and row["EXTENSION_TYPE"] == "CONNECTOR" and
            row["SOURCE"] == "EXTERNAL", "DuckLake extension is not the intended external connector")
    require(row["EXTENSION_VERSION"] == expected, "Loaded DuckLake build identity is null, stale or unexpected")


def alive_versions(text):
    lines = text.splitlines()
    require(lines, "Missing runtime inventory header")
    columns = lines[0].split("\t")
    require(len(set(columns)) == len(columns) and {"Alive", "Version"} <= set(columns),
            "Malformed runtime inventory header")
    versions = set()
    for line in lines[1:]:
        values = line.split("\t")
        require(len(values) == len(columns), "Malformed runtime inventory row")
        row = dict(zip(columns, values))
        require(row["Alive"] in {"true", "false"}, "Malformed runtime liveness value")
        if row["Alive"] == "true":
            require(row["Version"] not in {"", "NULL", "\\N"}, "Missing live runtime source version")
            versions.add(row["Version"])
    return sorted(versions)


class ProviderAttestation:
    def __init__(self, deployment, docker, inspect):
        self.d = deployment
        self.docker = docker
        self.inspect = inspect
        self.gates = deployment.state["attestation_gates"] = {}

    def gate(self, name, passed=True, required=True):
        self.gates[name] = {"required": required, "passed": passed}

    def query(self, sql, headers=False):
        fe = self.d.confirm_process()
        flags = ["-B", "-r"] + ([] if headers else ["-N"])
        return self.docker("exec", fe["Id"], "mysql", "-h127.0.0.1", "-P9030", "-uroot", *flags, "-e", sql,
                           timeout=min(15, max(1, self.d.startup_timeout)))

    def backend_spec(self):
        self.d.revalidate()
        config = json.loads(self.docker("compose", "-f", self.d.compose, "config", "--format", "json"))
        require(sha256(json.dumps(config, sort_keys=True, separators=(",", ":")).encode()) ==
                self.d.target["compose_sha256"], "Rendered runtime configuration changed")
        service = config["services"]["doris-be"]
        name = service.get("container_name")
        require(isinstance(name, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", name) and service.get("image"),
                "Expected one explicit BE container and image")
        self.be_name, self.be_ref = name, service["image"]
        self.be_image = json.loads(self.docker("image", "inspect", self.be_ref))[0]["Id"]

    def backend(self, required=True):
        ids = self.docker("container", "ls", "--all", "--filter", f"name=^/{self.be_name}$", "--format", "{{.ID}}").splitlines()
        require(len(ids) <= 1 and (ids or not required), "Missing or ambiguous owned BE container")
        if not ids:
            return None
        instance = self.inspect(ids[0])
        labels = instance["Config"].get("Labels") or {}
        require(instance["Name"].removeprefix("/") == self.be_name and
                labels.get("com.docker.compose.project") == self.d.target["project"] and
                labels.get("com.docker.compose.service") == "doris-be", "Foreign BE container ownership")
        require(instance["Image"] == self.be_image, "BE image differs from selected image; explicit migration required")
        return instance

    def run(self):
        d = self.d
        d.confirm_process()
        self.gate("fresh_fe_process")
        # Although the registry is FE-owned, the API-24 inventory scan requires
        # an available BE. FE-local SHOW FRONTENDS alone is the earlier FE gate.
        self.backend_spec()
        self.backend(required=False)  # Check ownership BEFORE any compose mutation.
        self.docker("compose", "-f", d.compose, "up", "-d", "--no-deps", "--no-build", "--pull", "never", "doris-be")
        be = self.backend()
        require(be["State"]["Running"], "Selected BE is not running")
        deadline = time.monotonic() + d.startup_timeout
        while True:
            output = self.query("SHOW BACKENDS", headers=True)
            # Some mysql clients omit even the header for an empty result. This
            # is not a passing gate: wait for registered live rows or time out.
            be_versions = alive_versions(output) if output else []
            if be_versions:
                break
            require(time.monotonic() < deadline, "BE registration timed out")
            time.sleep(2)
        self.gate("be_registered")
        fe_versions = alive_versions(self.query("SHOW FRONTENDS", headers=True))
        expected_source = os.environ.get("DORIS_EXPECTED_SOURCE", "62e3d81a42b")
        require(re.fullmatch(r"[0-9a-f]{7,40}", expected_source), "Invalid expected Doris source pin")
        expected = "doris-0.0.0-" + expected_source
        d.state["runtime_sources"] = {"expected": expected, "fe": fe_versions, "be": be_versions}
        require(fe_versions == [expected], f"Unexpected FE/BE source: FE={fe_versions}; expected exact pin '{expected_source}'")
        self.gate("fe_source_exact")
        stock = self.be_ref == STOCK_BE
        if not stock:
            require(fe_versions == be_versions, f"FE/BE source mismatch: FE={fe_versions}, BE={be_versions}")
            self.gate("be_source_exact")
        else:
            self.gate("be_source_exact", passed=None, required=False)
        rows = inventory_rows(self.query(INVENTORY_SQL))
        d.state["provider_inventory"] = rows
        validate_inventory(rows, d.state["artifact"]["build_id"])
        self.gate("loaded_provider")
        d.event("loaded-provider-identity-matched")
        require(self.query("SELECT 1 AS wire_compat_ok") == "1", "Runtime SELECT 1 readiness failed")
        self.gate("sql_ready")

        # A live-volume check complements, but never substitutes for, the registry
        # query above. Confirm the same process again after the read-only helper.
        d.worker("verify", candidate=True, results=("verified.json",))
        self.gate("installed_files_exact")
        d.confirm_process()
        after_be = self.backend()
        require(after_be["Id"] == be["Id"] and after_be["State"]["Running"] and
                after_be["State"]["StartedAt"] == be["State"]["StartedAt"], "BE process changed during attestation")
        self.gate("stable_processes")
        qualification = "stock-be-compatibility-experiment" if stock else "matching-pinned-fe-be"
        d.state["runtime_images"] = {
            "fe": {"reference": d.target["fe_image"], "id": d.fe_image},
            "be": {"reference": self.be_ref, "id": be["Image"], "container": self.be_name,
                   "container_id": be["Id"], "started_at": be["State"]["StartedAt"]}}
        require(all(not value["required"] or value["passed"] for value in self.gates.values()), "Required identity gate failed")
        receipt = {"schema": "doris-plugin-deployment-attestation-v1", "status": "deployment-attested",
                   "owner": d.token, "target": d.target, "artifact": d.state["artifact"],
                   "prior_process": d.prior, "fe_process": d.state["new_process"],
                   "runtime_images": d.state["runtime_images"], "runtime_sources": d.state["runtime_sources"],
                   "provider_inventory": rows, "gates": self.gates, "qualification": qualification,
                   "tooling_sha256": d.state["tooling_sha256"],
                   "runtime_config_sha256": d.state["runtime_config_sha256"],
                   "loaded_provider_verified": True, "smoke_correctness_passed": False,
                   "run_success": None, "f25_complete": False,
                   "attested_at": d.timestamp()}
        d.write_record("deployment-attested.json", receipt)
        d.state.update(deployment_attested=True, loaded_provider_verified=True, qualification=qualification)
        d.event("deployment-attested")
