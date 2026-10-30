"""The live acceptance recorder must not touch foreign/lake targets on a bad gate."""

from contextlib import redirect_stderr, redirect_stdout
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import plugin_acceptance_docker as guarded
from plugin_attest import INVENTORY_SQL


class AcceptanceDockerGuardTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="acceptance-guard-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.spec = {"docker": "/real/docker", "projects": ["owned-doris", "owned-substrate"],
                     "plugin_volume": "owned-plugin", "claim_name": "owned-claim", "compose_files": [str(self.root / "compose.yml")],
                     "log": str(self.root / "commands.jsonl"), "inject_stale": False,
                     "expected_actual": "build-B", "stale_version": "build-A"}

    def invoke(self, argv, result=None, labels=None, mounts=None):
        spec = self.root / "guard.json"
        spec.write_text(json.dumps(self.spec))
        inspect = SimpleNamespace(returncode=0, stdout=json.dumps([{"Config": {"Labels": labels or {
            "com.docker.compose.project": "owned-doris"}}, "Mounts": mounts or []}]), stderr="")
        result = result or SimpleNamespace(returncode=0, stdout="", stderr="")

        def run(args, **_):
            return inspect if args[1:3] == ["container", "inspect"] else result

        with patch.dict("os.environ", {"F24_ACCEPTANCE_DOCKER": str(spec)}), patch("sys.argv", ["docker", *argv]), \
                patch.object(guarded.subprocess, "run", side_effect=run) as calls, \
                redirect_stdout(io.StringIO()) as stdout, redirect_stderr(io.StringIO()):
            code = guarded.main()
        return code, stdout.getvalue(), calls.call_args_list

    def test_lake_allocations_are_blocked_before_any_docker_call(self):
        for argv in (["exec", "trino-ducklake-postgres", "psql", "-c", "SELECT gen_random_uuid()"],
                     ["exec", "owned-fe", "mysql", "-e", "create catalog accidental"],
                     ["exec", "owned-pg", "psql", "-c", "create database accidental"],
                     ["run", "minio/mc:latest"], ["compose", "-f", str(self.root / "compose.yml"), "run", "bootstrap"]):
            with self.subTest(argv=argv):
                code, _, calls = self.invoke(argv)
                self.assertEqual(code, 97)
                self.assertEqual(calls, [])

    def test_foreign_compose_or_volume_deletion_is_never_forwarded(self):
        for argv in (["compose", "-f", "/foreign/docker-compose.yml", "up", "-d"],
                     ["volume", "rm", "foreign-volume"], ["container", "stop", "foreign-fe"]):
            with self.subTest(argv=argv):
                code, _, calls = self.invoke(argv)
                self.assertEqual(code, 97)
                self.assertEqual(calls, [])

    def test_foreign_container_is_not_owned_merely_by_a_matching_mount(self):
        code, _, calls = self.invoke(["stop", "foreign-fe"], labels={"com.docker.compose.project": "foreign"},
                                    mounts=[{"Name": "owned-plugin"}])
        self.assertEqual(code, 97)
        self.assertEqual(len(calls), 1)  # Read-only inspection, never stop.

    def test_normal_owned_command_is_forwarded(self):
        code, _, calls = self.invoke(["start", "owned-fe"])
        self.assertEqual(code, 0)
        self.assertEqual(calls[-1].args[0], ["/real/docker", "start", "owned-fe"])

    def test_stale_injection_changes_only_a_verified_real_inventory_version(self):
        self.spec["inject_stale"] = True
        result = SimpleNamespace(returncode=0, stdout="ducklake\tCONNECTOR\tbuild-B\tEXTERNAL\n", stderr="")
        code, stdout, calls = self.invoke(["exec", "owned-fe", "mysql", "-e", INVENTORY_SQL], result=result)
        self.assertEqual(code, 0)
        self.assertEqual(stdout, "ducklake\tCONNECTOR\tbuild-A\tEXTERNAL\n")
        self.assertEqual(len(calls), 2)
        records = [json.loads(line) for line in (self.root / "commands.jsonl").read_text().splitlines()]
        self.assertEqual(records[-1]["real_inventory"], [["ducklake", "CONNECTOR", "build-B", "EXTERNAL"]])

    def test_unexpected_real_inventory_is_never_fabricated_as_a_good_injection(self):
        self.spec["inject_stale"] = True
        result = SimpleNamespace(returncode=0, stdout="ducklake\tCONNECTOR\tNULL\tEXTERNAL\n", stderr="")
        code, stdout, _ = self.invoke(["exec", "owned-fe", "mysql", "-e", INVENTORY_SQL], result=result)
        self.assertEqual(code, 98)
        self.assertEqual(stdout, "")


if __name__ == "__main__":
    unittest.main()
