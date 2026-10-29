"""Opt-in real Docker transport/lifecycle test; a disposable Python process, NOT Doris.

F24_DOCKER_TEST=1 python3 -m unittest discover -s compose -p test_plugin_deploy_docker.py
Uses the existing local Python image, synthetic plugin bytes and exclusively
UUID-owned volumes/containers. Does not contact PostgreSQL/S3 or the retained FE.
"""

import json
import os
from pathlib import Path
import signal
import shutil
import subprocess
import sys
import tempfile
import unittest
import uuid

from plugin_deploy import Deployment, OWNER, claim_name, docker, inspect, process_record
from plugin_preflight import target_from_compose, docker_json
from plugin_test_fixture import write_plugin


@unittest.skipUnless(os.environ.get("F24_DOCKER_TEST") == "1", "opt-in disposable Docker transport test")
class DockerDeploymentTests(unittest.TestCase):
    @staticmethod
    def lifecycle_only(deployment):
        """Exercise B primitives, not run()/C. A SQL stub cannot qualify Doris."""
        with deployment.ownership():
            deployment.revalidate()
            deployment.fe_image = docker_json("image", "inspect", deployment.target["fe_image"])[0]["Id"]
            deployment.prior = process_record(deployment.fe())
            deployment.state["prior_process"] = deployment.prior
            if deployment.prior["running"]:
                deployment.state["prior_cluster_id"] = deployment.metadata_cluster_id(deployment.prior["id"])
            deployment.preserve_candidate()
            deployment.backup()
            deployment.install()
            deployment.start()
            deployment.state["lifecycle_complete"] = True
            deployment.event("lifecycle-only-test-not-provider-attested")

    def test_transport_restart_backup_claim_collision_and_interrupted_owner(self):
        base = Path("/tmp/opencode")
        with tempfile.TemporaryDirectory(prefix="f24-c-transport-", dir=base) as temporary:
            root = Path(temporary)
            identifier = "f24-c-transport-" + uuid.uuid4().hex[:16]
            compose = root / "compose.json"
            volume = identifier + "-plugin"
            meta = identifier + "-meta"
            config = {"name": identifier, "services": {"doris-fe": {
                "image": "python:3.12-slim", "container_name": identifier + "-fe",
                "entrypoint": ["python3", "-c", "import signal,sys,time; "
                               "signal.signal(signal.SIGTERM,lambda *_:sys.exit(0)); time.sleep(2147483647)"],
                "volumes": [f"plugin:/opt/apache-doris/fe/plugins/connector/ducklake",
                            "meta:/opt/apache-doris/fe/doris-meta"]}},
                "volumes": {"plugin": {"name": volume}, "meta": {"name": meta}}}
            compose.write_text(json.dumps(config))
            runtime = root / ".fe.conf.runtime"
            runtime.write_bytes(b"priority_networks = 198.51.100.0/24\n# retained test config\n")
            original_config = runtime.read_bytes()
            artifact = write_plugin(root / "selected.zip")
            mysql = root / "mysql"
            mysql.write_text("#!/bin/sh\nexit 0\n")
            mysql.chmod(0o755)
            preflight = {"artifact": artifact, "staged_zip": str(root / "selected.zip"),
                         "staged_manifest": str(root / "selected.identity.json")}
            child = None
            interrupted_claim = None
            try:
                docker("compose", "-f", compose, "up", "-d", "--pull", "never")
                target = target_from_compose(compose)
                preflight["target"] = target
                fe = target["fe_container"]
                docker("cp", mysql, f"{fe}:/usr/local/bin/mysql")
                docker("exec", fe, "python3", "-c", "from pathlib import Path; "
                       "p=Path('/opt/apache-doris/fe/doris-meta/image'); p.mkdir(); "
                       "(p/'VERSION').write_text('clusterId=777\\nprivateToken=DO-NOT-RECORD\\n'); "
                       "(p/'sentinel').write_text('persistent metadata'); "
                       "p=Path('/opt/apache-doris/fe/plugins/connector/ducklake'); "
                       "(p/'lib').mkdir(); (p/'lib/stale.jar').write_text('old bytes'); "
                       "(p/'.previous').write_text('previous manifest')")
                original = inspect(fe)
                first = Deployment(compose, preflight, root / "evidence", startup_timeout=5)
                self.lifecycle_only(first)
                second = Deployment(compose, preflight, root / "other-checkout", startup_timeout=5)
                docker("stop", "--time", "1", fe)
                self.lifecycle_only(second)
                after = inspect(fe)
                self.assertEqual(original["Id"], after["Id"])
                self.assertGreater(after["State"]["StartedAt"], original["State"]["StartedAt"])
                self.assertEqual(runtime.read_bytes(), original_config)
                self.assertEqual(docker("exec", fe, "cat", "/opt/apache-doris/fe/doris-meta/image/sentinel"),
                                 "persistent metadata")
                for deployment in (first, second):
                    self.assertTrue(deployment.state["lifecycle_complete"])
                    self.assertFalse(deployment.state["loaded_provider_verified"])
                    self.assertFalse((deployment.evidence / "deployment-attested.json").exists())
                    self.assertNotIn("DO-NOT-RECORD", (deployment.evidence / "lifecycle.json").read_text())

                holder = Deployment(compose, preflight, root / "holder")
                contender = Deployment(compose, preflight, root / "contender")
                with holder.ownership():
                    with self.assertRaisesRegex(RuntimeError, "occupied"):
                        with contender.ownership():
                            self.fail("Occupied claim was stolen")
                    self.assertTrue(inspect(fe)["State"]["Running"])

                # A distinct client process dies without its finally block. The
                # name remains occupied until this test explicitly recovers its
                # OWN UUID-scoped claim; production never performs this recovery.
                (root / "preflight.json").write_text(json.dumps(preflight))
                script = ("import json,sys,time; from pathlib import Path; from plugin_deploy import Deployment; "
                          "r=Path(sys.argv[1]); p=json.loads((r/'preflight.json').read_text()); "
                          "d=Deployment(r/'compose.json',p,r/'interrupted'); "
                          "c=d.ownership(); c.__enter__(); print(d.claim,flush=True); time.sleep(300)")
                env = dict(os.environ, PYTHONPATH=str(Path(__file__).resolve().parent), PYTHONDONTWRITEBYTECODE="1")
                child = subprocess.Popen([sys.executable, "-c", script, str(root)], env=env,
                                         stdout=subprocess.PIPE, text=True)
                interrupted_claim = child.stdout.readline().strip()
                self.assertTrue(interrupted_claim)
                os.kill(child.pid, signal.SIGKILL)
                child.wait(timeout=10)
                abandoned = inspect(interrupted_claim)
                self.assertTrue(abandoned["Config"]["Labels"].get(OWNER))
                blocked = Deployment(compose, preflight, root / "blocked")
                with self.assertRaisesRegex(RuntimeError, "occupied"):
                    with blocked.ownership():
                        self.fail("Interrupted owner's claim was stolen")
                self.assertEqual(abandoned["Name"].lstrip("/"), claim_name(volume))

                summary = {"scope": "real Docker, simulated FE and synthetic provider; NOT Doris/loaded-provider",
                           "target": target, "prior_process": original["State"]["StartedAt"],
                           "new_process": after["State"]["StartedAt"], "metadata_preserved": True,
                           "running_and_stopped_fe_restart": True, "competing_client_refused": True,
                           "killed_owner_claim_retained": True,
                           "old_files": json.loads((first.evidence / "previous.json").read_text())["files"],
                           "artifact": artifact}
                retained = base / "f24-c-docker-transport-evidence" / identifier
                for name, deployment in (("running", first), ("stopped", second)):
                    shutil.copytree(deployment.evidence, retained / name)
                summary["retained_evidence"] = str(retained)
                (base / "f24-c-docker-transport-results.json").write_text(json.dumps(summary, indent=2))
            finally:
                if child and child.poll() is None:
                    child.kill()
                    child.wait()
                if child and child.stdout:
                    child.stdout.close()
                if interrupted_claim:
                    owner = inspect(interrupted_claim)["Config"]["Labels"][OWNER]
                    self.assertTrue(owner)
                    docker("stop", "--time", "1", interrupted_claim)
                    docker("rm", interrupted_claim)
                docker("compose", "-f", compose, "down", "-v")


if __name__ == "__main__":
    unittest.main()
