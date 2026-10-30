"""F24-B/C: serialized stopped replacement and load-time provider attestation.

The claim is held through attestation and until the supplied smoke command exits.
Only cooperating clients honor this Docker claim. F25 correctness remains separate.
"""

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time
import uuid

from plugin_preflight import docker_json, file_hash, require, sha256, target_from_compose, verify_bundle
from plugin_volume import snapshot, verify_backup
from plugin_attest import ProviderAttestation


OWNER = "dev.brikk.ducklake.deployment.owner"
VOLUME = "dev.brikk.ducklake.deployment.volume"
HELPER_IMAGE = "python:3.12-slim"


def docker(*args, timeout=240):
    result = subprocess.run(["docker", *map(str, args)], text=True, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"docker {args[0]} failed: {result.stderr.strip()}")
    # mysql batch rows may end in legitimate empty columns. Preserve tabs and
    # spaces; only remove transport line endings, never data-bearing whitespace.
    return result.stdout.rstrip("\r\n")


def inspect(container):
    return json.loads(docker("container", "inspect", container))[0]


def claim_name(volume):
    # Container names are atomic within the selected daemon. Different checkouts,
    # projects, remote clients and Docker contexts reaching it share this key.
    return "doris-ducklake-deploy-" + sha256(volume.encode())[:40]


def process_record(instance):
    if instance is None:
        return None
    return {"id": instance["Id"], "image": instance["Image"],
            "started_at": instance["State"]["StartedAt"],
            "running": instance["State"]["Running"],
            "hostname": instance["Config"].get("Hostname"),
            "mounts": sorted(
                [{k: m.get(k) for k in ("Type", "Name", "Source", "Destination", "RW")}
                 for m in instance.get("Mounts", [])], key=lambda mount: mount["Destination"])}


class Deployment:
    def __init__(self, compose, preflight, evidence_root, startup_timeout=180):
        self.compose = Path(compose).resolve()
        self.preflight = preflight
        self.target = preflight["target"]
        self.token = uuid.uuid4().hex
        self.claim = None
        self.helpers = []
        self.install_boundary = False
        self.ready = False
        self.startup_timeout = startup_timeout
        self.evidence = Path(evidence_root).resolve() / ("deploy-" + self.token)
        self.evidence.mkdir(parents=True, mode=0o700)
        self.state = {"schema": "doris-plugin-deployment-lifecycle-v1", "owner": self.token,
                      "target": self.target, "loaded_provider_verified": False, "events": []}
        self.prior = None

    def event(self, name, **values):
        self.state["events"].append({"event": name, "at": self.timestamp(), **values})
        self.write_record("lifecycle.json", self.state)

    @staticmethod
    def timestamp():
        return datetime.now(timezone.utc).isoformat()

    def write_record(self, name, value):
        path = self.evidence / name
        temporary = path.with_suffix(".tmp")
        temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")
        temporary.replace(path)

    def revalidate(self):
        current = target_from_compose(self.compose)
        require(all(current[k] == self.target[k] for k in
                    ("project", "fe_service", "fe_container", "plugin_volume", "plugin_volume_key", "fe_image")),
                "Deployment target changed after preflight")
        require(current["compose_sha256"] == self.target["compose_sha256"],
                "Rendered compose configuration changed after preflight")
        return current

    def fe(self):
        current = self.revalidate()
        return inspect(self.target["fe_container"]) if current["fe_present"] else None

    def owned(self, container):
        instance = inspect(container)
        require((instance["Config"].get("Labels") or {}).get(OWNER) == self.token,
                "Helper/claim owner token changed; refusing cleanup")
        return instance

    @contextmanager
    def ownership(self):
        image = docker_json("image", "inspect", HELPER_IMAGE)[0]["Id"]
        name = claim_name(self.target["plugin_volume"])
        try:
            # Never use --replace, --rm, or force-removal for a claim. A dead
            # process deliberately leaves an occupied claim for manual recovery.
            self.claim = docker("create", "--pull=never", "--name", name, "--network", "none",
                                "--label", f"{OWNER}={self.token}", "--label", f"{VOLUME}={self.target['plugin_volume']}",
                                "--workdir", "/work", "--entrypoint", "python3", image,
                                "-c", "import signal,sys,time; signal.signal(signal.SIGTERM,lambda *_:sys.exit(0)); "
                                      "time.sleep(2147483647)")
        except RuntimeError as error:
            raise RuntimeError(f"Deployment claim occupied or acquisition failed ({name}); never auto-steal. {error}") from error
        self.image = image
        self.event("claim-acquired", claim=self.claim, claim_name=name, helper_image=image)
        try:
            docker("start", self.claim)
            yield
        finally:
            # A running installer must not outlive the claim. If ownership or
            # stopping is uncertain, retain the claim instead of permitting a rival.
            self.cleanup_helpers()
            if not self.state.get("unsafe_helper_state") and not self.state.get("unsafe_fe_state"):
                self.remove_owned(self.claim)
                self.event("claim-released")
            else:
                self.event("claim-retained-for-recovery", claim=self.claim)

    def cleanup_helpers(self):
        for helper in reversed(self.helpers.copy()):
            try:
                self.remove_owned(helper)
                self.helpers.remove(helper)
                self.state["helpers"] = self.helpers.copy()
            except (RuntimeError, ValueError, OSError, subprocess.SubprocessError) as error:
                self.state["unsafe_helper_state"] = True
                self.event("helper-cleanup-refused", helper=helper, error=str(error))

    def remove_owned(self, container):
        instance = self.owned(container)
        if instance["State"]["Running"]:
            docker("stop", "--time", "10", instance["Id"], timeout=30)
            require(not self.owned(instance["Id"])["State"]["Running"], "Owned helper is still running")
        docker("rm", instance["Id"])

    def copy_worker(self, container, candidate=False, previous=False):
        source = Path(__file__).resolve().parent
        for name in ("plugin_volume.py", "plugin_preflight.py"):
            docker("cp", source / name, f"{container}:/work/{name}")
        if candidate:
            for name in ("plugin.zip", "plugin.identity.json"):
                docker("cp", self.evidence / name, f"{container}:/work/{name}")
        if previous:
            docker("cp", self.evidence / "previous.json", f"{container}:/work/previous.json")

    def worker(self, operation, *, readonly=True, candidate=False, previous=False, results=()):
        helper = docker("create", "--pull=never", "--network", "none", "--label", f"{OWNER}={self.token}",
                        "--workdir", "/work", "-v", f"{self.target['plugin_volume']}:/dest:{'ro' if readonly else 'rw'}",
                        "--entrypoint", "python3", self.image, "/work/plugin_volume.py", operation)
        self.helpers.append(helper)
        self.state["helpers"] = self.helpers.copy()
        self.event("worker-created", helper=helper, operation=operation, read_only=readonly)
        self.copy_worker(helper, candidate=candidate, previous=previous)
        docker("start", "--attach", helper)
        instance = self.owned(helper)
        require(not instance["State"]["Running"] and instance["State"]["ExitCode"] == 0,
                f"Plugin worker {operation} failed (exit {instance['State'].get('ExitCode')})")
        for name in results:
            docker("cp", f"{helper}:/work/{name}", self.evidence / name)
        self.remove_owned(helper)
        self.helpers.remove(helper)
        self.state["helpers"] = self.helpers.copy()
        self.event("worker-finished", helper=helper, operation=operation)

    def preserve_candidate(self):
        shutil.copyfile(self.preflight["staged_zip"], self.evidence / "plugin.zip")
        shutil.copyfile(self.preflight["staged_manifest"], self.evidence / "plugin.identity.json")
        artifact = verify_bundle(self.evidence / "plugin.zip", self.evidence / "plugin.identity.json")
        require(artifact == self.preflight["artifact"], "Frozen artifact changed after preflight")
        self.state["artifact"] = artifact
        self.copy_worker(self.claim, candidate=True)
        docker("exec", self.claim, "python3", "/work/plugin_volume.py", "prepare")
        docker("cp", f"{self.claim}:/work/prepared.json", self.evidence / "prepared.json")
        self.event("candidate-prepared")

    def backup(self):
        current = self.revalidate()
        if current["volume_present"]:
            self.worker("snapshot", results=("previous.tar", "previous.json"))
        else:
            empty = self.evidence / "empty"
            empty.mkdir()
            snapshot(empty, self.evidence)
            empty.rmdir()
        receipt = json.loads((self.evidence / "previous.json").read_text())
        verify_backup(self.evidence / "previous.tar", receipt)
        self.event("rollback-preserved", archive_sha256=receipt["archive_sha256"], files=len(receipt["files"]))

    def stop_fe(self):
        instance = self.fe()
        if self.prior is not None:
            require(instance is not None and instance["Id"] == self.prior["id"], "FE container changed before stop")
        else:
            require(instance is None, "FE appeared after ownership snapshot")
        if instance is not None:
            docker("stop", "--time", "60", instance["Id"], timeout=90)
            stopped = self.fe()
            require(stopped is not None and stopped["Id"] == instance["Id"] and
                    not stopped["State"]["Running"] and not stopped["State"].get("Restarting", False) and
                    not stopped["State"].get("Paused", False), "FE stop was not confirmed")
        self.event("fe-stopped")

    def stage_config(self):
        # Preserve an existing runtime config byte-for-byte (including its elected
        # priority network). Only bootstrap the repository's missing default copy.
        runtime = self.compose.parent / ".fe.conf.runtime"
        if self.state.get("runtime_config_sha256") is not None:
            require(runtime.is_file() and file_hash(runtime) == self.state["runtime_config_sha256"],
                    "Runtime configuration changed after ownership snapshot")
        if not runtime.exists():
            require(self.prior is None, "Existing FE runtime configuration is missing")
            with runtime.open("xb") as stream:
                stream.write((self.compose.parent / "fe.conf").read_bytes())
                stream.write(b"\npriority_networks = 172.30.80.0/24\n")
        self.state["runtime_config_sha256"] = file_hash(runtime)
        self._runtime_config_before_start = runtime.read_bytes()

    def verify_runtime_config(self):
        runtime = self.compose.parent / ".fe.conf.runtime"
        observed = runtime.read_bytes()
        digest = sha256(observed)
        if digest == self.state["runtime_config_sha256"]:
            return
        # The pinned image's init_fe.sh appends this exact duplicate when it
        # initializes empty metadata. Permit no other byte change, only once,
        # and never on a previously started container's replacement/restart.
        original = self._runtime_config_before_start
        values = [value.strip() for value in re.findall(
            rb"(?m)^[ \t]*priority_networks[ \t]*=[ \t]*([^\r\n#]+)$", original)]
        first_boot = self.prior is None or self.prior["started_at"].startswith("0001-")
        require(first_boot and not self.state.get("runtime_config_initialized") and original.endswith(b"\n") and
                values and len(set(values)) == 1 and
                observed == original + b"priority_networks = " + values[0] + b"\n",
                "FE runtime configuration changed during restart")
        before = self.state["runtime_config_sha256"]
        self.state.update(runtime_config_initialized=True, runtime_config_sha256=digest)
        self.event("first-boot-runtime-config-initialized", before_sha256=before, after_sha256=digest,
                   allowed_change="one exact duplicate of the existing priority_networks value")

    def install(self):
        self.stop_fe()
        self.install_boundary = True
        self.stage_config()
        self.assert_stopped()
        docker("volume", "create", "--label", f"com.docker.compose.project={self.target['project']}",
               "--label", f"com.docker.compose.volume={self.target['plugin_volume_key']}", self.target["plugin_volume"])
        self.event("install-started")
        self.assert_stopped()
        self.worker("install", readonly=False, candidate=True, previous=True, results=("installed.json",))
        self.worker("verify", candidate=True, results=("verified.json",))
        self.event("installed-files-verified")

    def assert_stopped(self):
        instance = self.fe()
        require(instance is None or (not instance["State"]["Running"] and
                not instance["State"].get("Restarting", False) and not instance["State"].get("Paused", False)),
                "FE is not stopped at the plugin-write boundary")
        require(self.prior is None and instance is None or self.prior is not None and instance is not None and
                instance["Id"] == self.prior["id"], "FE container changed at the plugin-write boundary")

    def wait_ready(self):
        deadline = time.monotonic() + self.startup_timeout
        while True:
            instance = self.fe()
            if instance is not None and instance["State"]["Running"]:
                started = instance["State"]["StartedAt"]
                require(started and not started.startswith("0001-") and
                        (self.prior is None or started > self.prior["started_at"]), "FE process is not fresh")
                require(instance["Image"] == self.fe_image, "Started FE image changed")
                if self.prior is not None:
                    record = process_record(instance)
                    changed = [k for k in ("id", "image", "hostname", "mounts") if record[k] != self.prior[k]]
                    require(not changed, f"FE identity or persistent mounts changed during restart: {', '.join(changed)}")
                try:
                    # FE-local readiness must not wait for BE: a first cluster's
                    # BE starts only after this boundary (same as FE healthcheck).
                    docker("exec", instance["Id"], "mysql", "-h127.0.0.1", "-P9030", "-uroot", "-e", "SHOW FRONTENDS",
                           timeout=min(10, max(1, self.startup_timeout)))
                    if self.state.get("prior_cluster_id") is not None:
                        require(self.metadata_cluster_id(instance["Id"]) == self.state["prior_cluster_id"],
                                "FE persisted cluster ID changed across restart")
                    self.verify_runtime_config()
                    self.state["new_process"] = process_record(instance)
                    self.ready = True
                    self.event("fresh-fe-sql-ready")
                    return
                except (RuntimeError, subprocess.TimeoutExpired):
                    pass
            if time.monotonic() >= deadline:
                raise RuntimeError("FE startup/readiness timed out")
            time.sleep(2)

    def metadata_cluster_id(self, container):
        try:
            content = docker("exec", container, "cat", "/opt/apache-doris/fe/doris-meta/image/VERSION", timeout=10)
        except (RuntimeError, subprocess.TimeoutExpired):
            return None
        # VERSION may also contain a private token. Never persist the raw file.
        for line in content.splitlines():
            key, _, value = line.partition("=")
            if key.strip() == "clusterId" and value.strip().isdigit():
                return value.strip()
        return None

    def start(self):
        # No metadata reset, compose recreation, BE stop or rolling image upgrade.
        instance = self.fe()
        require(instance is None or not instance["State"]["Running"], "FE restarted before verified installation")
        if instance is None:
            docker("compose", "-f", self.compose, "up", "-d", "--no-deps", "--no-build", "--pull", "never", "doris-fe")
        else:
            docker("start", instance["Id"])
        self.event("fe-start-requested")
        self.wait_ready()

    def fail_closed(self):
        if not self.install_boundary or self.state.get("deployment_attested"):
            return
        try:
            instance = self.fe()
            if instance is not None:
                docker("stop", "--time", "60", instance["Id"], timeout=90)
                require(not self.fe()["State"]["Running"], "FE remains running after deployment failure")
            self.event("failed-fe-kept-stopped")
        except (RuntimeError, ValueError, OSError, subprocess.SubprocessError) as error:
            self.state["unsafe_fe_state"] = True
            self.event("failed-fe-stop-unconfirmed", error=str(error))

    def confirm_process(self):
        instance = self.fe()
        require(instance is not None and instance["State"]["Running"] and
                process_record(instance) == self.state["new_process"], "FE process changed after fresh startup")
        require(file_hash(self.compose.parent / ".fe.conf.runtime") == self.state["runtime_config_sha256"],
                "Runtime config changed after fresh startup")
        return instance

    def run(self, command=()):
        try:
            self._run(command)
            require(self.state.get("deployment_attested"), "Deployment attestation was not completed")
            self.write_record("run-result.json", {"schema": "doris-plugin-run-result-v1", "owner": self.token,
                              "status": "command-completed" if command else "attested-only", "run_success": True,
                              "deployment_attested": True, "smoke_correctness_passed": False, "f25_complete": False,
                              "finished_at": self.timestamp()})
        except BaseException as error:
            # Retain the successful observation as history, but never leave a
            # current passing receipt behind after a failed overall invocation.
            receipt = self.evidence / "deployment-attested.json"
            if receipt.exists():
                receipt.rename(self.evidence / "deployment-attested-before-error.json")
            self.write_record("run-result.json", {"schema": "doris-plugin-run-result-v1", "owner": self.token,
                              "status": "failed", "run_success": False,
                              "deployment_attested": bool(self.state.get("deployment_attested")),
                              "smoke_correctness_passed": False, "f25_complete": False,
                              "error": str(error), "finished_at": self.timestamp()})
            raise

    def _run(self, command=()):
        print(f"[smoke] Lifecycle evidence: {self.evidence}", flush=True)
        with self.ownership():
            try:
                self.revalidate()
                self.fe_image = docker_json("image", "inspect", self.target["fe_image"])[0]["Id"]
                self.prior = process_record(self.fe())
                require(self.prior is None or self.prior["image"] == self.fe_image,
                        "Existing FE image differs from selected image; explicit migration required")
                self.state["prior_process"] = self.prior
                source = Path(__file__).resolve().parent
                self.state["tooling_sha256"] = {name: file_hash(source / name) for name in
                    ("plugin_deploy.py", "plugin_attest.py", "plugin_volume.py", "plugin_preflight.py", "smoke.sh", "smoke_run.sh")}
                runtime = self.compose.parent / ".fe.conf.runtime"
                require(not runtime.exists() or runtime.is_file(), "Runtime config is not a regular file")
                require(self.prior is None or runtime.is_file(), "Existing FE runtime configuration is missing")
                require(runtime.is_file() or (self.compose.parent / "fe.conf").is_file(), "Bootstrap FE config is missing")
                if runtime.is_file():
                    self.state["runtime_config_sha256"] = file_hash(runtime)
                if self.prior and self.prior["running"]:
                    self.state["prior_cluster_id"] = self.metadata_cluster_id(self.prior["id"])
                self.event("owned-target-revalidated")
                self.preserve_candidate()
                self.backup()
                if command:
                    substrate = self.compose.parent.parent.parent / "trino-ducklake/compose/docker-compose.yml"
                    docker("compose", "-f", substrate, "up", "-d", "--no-recreate", "--wait", "--wait-timeout", "120",
                           "postgres", "minio")
                    Path(os.environ.get("DORIS_CORPUS_DIR", "/tmp/ducklake-corpus")).mkdir(parents=True, exist_ok=True)
                self.install()
                self.start()
                self.state["lifecycle_complete"] = True
                self.event("lifecycle-complete-not-provider-attested")
                ProviderAttestation(self, docker, inspect).run()
                if command:
                    child = subprocess.Popen([*command, str(self.evidence / "lifecycle.json")], start_new_session=True)
                    try:
                        status = child.wait()
                    except BaseException:
                        try:
                            os.killpg(child.pid, signal.SIGTERM)
                        except ProcessLookupError:
                            pass
                        try:
                            child.wait(timeout=10)
                        except subprocess.TimeoutExpired:
                            os.killpg(child.pid, signal.SIGKILL)
                            child.wait()
                        raise
                    require(status == 0, f"Smoke command failed (exit {status})")
                    self.event("smoke-command-finished")
            except BaseException as error:
                try:
                    self.event("failed", error=str(error))
                finally:
                    try:
                        # Failed helpers remain volume consumers until removed.
                        # Quiesce them before revalidating the FE's sole mount.
                        self.cleanup_helpers()
                    finally:
                        self.fail_closed()
                raise

    def teardown(self):
        with self.ownership():
            try:
                self.revalidate()
                docker("compose", "-f", self.compose, "down", "-v")
                self.event("explicit-teardown-finished")
            except BaseException:
                # Teardown may still be in flight after a client timeout. Do not
                # permit a new deployer to race an uncertain destructive request.
                self.state["unsafe_fe_state"] = True
                raise


def interrupted(signum, _frame):
    raise RuntimeError(f"Deployment interrupted by signal {signum}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compose-file", required=True, type=Path)
    parser.add_argument("--evidence-root", required=True, type=Path)
    parser.add_argument("--startup-timeout", type=int, default=180)
    parser.add_argument("--down", action="store_true")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        require(args.startup_timeout > 0, "Startup timeout must be positive")
        preflight = {"target": target_from_compose(args.compose_file)} if args.down else json.load(sys.stdin)
        command = args.command[1:] if args.command[:1] == ["--"] else args.command
        deployment = Deployment(args.compose_file, preflight, args.evidence_root, args.startup_timeout)
        if args.down:
            deployment.teardown()
        else:
            deployment.run(command)
    except (RuntimeError, ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError) as error:
        print(f"Plugin deployment failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
