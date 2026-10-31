"""Opt-in F25 full S3 smoke and same-count tuple corruption refusal.

Uses F24's verified two-bundle, UUID-owned sandbox builder; F25 runs bundle B's
unchanged provider classes. No retained or shared service is a target.
"""

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

from plugin_acceptance import Acceptance, write_json
from plugin_deploy import claim_name
from plugin_preflight import require, file_hash


class FullSmokeAcceptance(Acceptance):
    def prepare(self):
        super().prepare()
        self.result["full_smoke_runner_sha256"] = file_hash(Path(__file__))
        shutil.copyfile(Path(__file__), self.root / "controller-sources/smoke_acceptance.py")
        source = self.source.parent.parent / "trino-ducklake/compose/bootstrap/init_ducklake.py"
        destination = self.substrate / "bootstrap"
        destination.mkdir()
        shutil.copyfile(source, destination / "init_ducklake.py")
        self.result["bootstrap_source_sha256"] = file_hash(source)
        self.result["schema"] = "doris-full-smoke-live-acceptance-v1"
        config = json.loads(self.substrate_file.read_text())
        config["services"]["bootstrap"] = {"image": self.result["images"]["python:3.12-slim"],
            "environment": {"DUCKDB_VERSION": "1.5.6", "PG_PORT": "5432"},
            "volumes": ["./bootstrap:/bootstrap:ro"],
            "command": ["sh", "-ec", 'pip install --quiet --no-cache-dir "duckdb==1.5.6"; exec python /bootstrap/init_ducklake.py']}
        write_json(self.substrate_file, config)

    def full_phase(self, name, corrupt=False):
        evidence = self.compose.parent / "build/smoke-deployments"
        before = set(evidence.glob("deploy-*")) if evidence.exists() else set()
        log = self.root / (name + "-docker.jsonl")
        spec = {"docker": self.docker, "projects": [self.doris_project, self.substrate_project],
                "compose_files": [str(self.doris_file), str(self.substrate_file)], "plugin_volume": self.plugin_volume,
                "claim_name": claim_name(self.plugin_volume), "log": str(log), "allow_owned_lake": True,
                "network": self.project + "-substrate-network", "postgres": self.pg, "minio": self.minio,
                "inject_bad_rows": corrupt}
        path = self.root / (name + "-docker-spec.json")
        write_json(path, spec)
        env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ.get("PATH", ""),
                   F24_ACCEPTANCE_DOCKER=str(path), PYTHONDONTWRITEBYTECODE="1", DORIS_FE_STARTUP_TIMEOUT="300",
                   DORIS_EXPECTED_SOURCE="62e3d81a42b", DORIS_BE_PLATFORM="linux/amd64", DORIS_CORPUS_DIR=str(self.root / "corpus"))
        self.started = True
        result = subprocess.run(["bash", str(self.compose / "smoke.sh"), "--plugin-zip", str(self.bundles["B"])],
                                cwd=self.root, env=env, text=True, capture_output=True, timeout=1200)
        (self.root / (name + ".log")).write_text(result.stdout + result.stderr)
        created = set(evidence.glob("deploy-*")) - before
        require(len(created) == 1, f"No unique full-smoke evidence for {name}")
        folder = created.pop()
        run = json.loads((folder / "run-result.json").read_text())
        ledger = json.loads((folder / "smoke-assertions.json").read_text()) if (folder / "smoke-assertions.json").exists() else {}
        records = [json.loads(line) for line in log.read_text().splitlines()]
        require(not any(r.get("scope_allowed") is False for r in records), "A foreign target was attempted")
        self.result["phases"][name] = {"exit_code": result.returncode, "evidence": str(folder), "run_result": run,
                                     "assertion_status": ledger.get("status"), "checks": ledger.get("checks", [])}
        if corrupt:
            require(result.returncode != 0 and not run["run_success"] and not run["smoke_correctness_passed"], "Wrong tuple reached success")
            require("Smoke complete" not in result.stdout and not (folder / "smoke-correctness.json").exists() and
                    not (folder / "deployment-attested.json").exists(), "Current passing evidence survived tuple corruption")
            injections = [r for r in records if r.get("injected_bad_rows")]
            require(len(injections) == 1, "The real-row corruption injection did not execute")
            require(any(r["case"] == "w2_rows" and r["outcome"] == "failed" for r in ledger["checks"]), "Wrong tuple failed at a different boundary")
            self.result["phases"][name]["injection"] = injections[0]
        else:
            require(result.returncode == 0 and run["run_success"] and run["smoke_correctness_passed"] and run["f25_complete"],
                    f"Full smoke failed at {ledger.get('aborted_phase')}; see {self.root / (name + '.log')}")
            self.result["phases"][name]["correctness"] = json.loads((folder / "smoke-correctness.json").read_text())
        write_json(self.root / "acceptance.json", self.result)

    def run(self):
        print(f"F25 evidence: {self.root}", flush=True)
        try:
            self.prepare()
            self.full_phase("full-supported")
            self.full_phase("same-count-corruption", corrupt=True)
            self.result.update(status="passed", full_supported_smoke_passed=True, same_count_corruption_refused=True, f25_complete=True)
        except BaseException as error:
            self.result.update(status="failed", error=str(error))
            raise
        finally:
            try:
                self.cleanup()
                if "protected_before" in self.result:
                    self.result["protected_after"] = self.protected()
                    require(self.result["protected_before"] == self.result["protected_after"], "Retained state changed")
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
    fixture = None
    try:
        fixture = FullSmokeAcceptance(args.bundle_a, args.bundle_b, args.duckdb, args.evidence_root)
        fixture.run()
    except (ValueError, OSError, KeyError, TypeError, RuntimeError, subprocess.SubprocessError) as error:
        print(f"F25 failed: {error}; evidence: {fixture.root if fixture else args.evidence_root}", file=sys.stderr)
        return 1
    print(f"PASS full supported smoke and same-count corruption refusal: {fixture.root}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
