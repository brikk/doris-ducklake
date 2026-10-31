"""API-24 inventory parsing and run-receipt boundaries, without Docker."""

import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from plugin_attest import INVENTORY_SQL, alive_versions, inventory_rows, validate_inventory
from plugin_deploy import Deployment, docker


BUILD = "doris-ducklake-v1-" + "a" * 64


class InventoryTests(unittest.TestCase):
    def test_docker_transport_preserves_empty_trailing_mysql_columns(self):
        result = SimpleNamespace(returncode=0, stdout="Alive\tVersion\tComment\ntrue\tpinned\t\n", stderr="")
        with patch("plugin_deploy.subprocess.run", return_value=result):
            output = docker("exec", "owned-fe", "mysql")
        self.assertTrue(output.endswith("\t"))
        self.assertEqual(alive_versions(output), ["pinned"])

    def test_actual_column_contract_accepts_exact_external_connector(self):
        rows = inventory_rows(f"ducklake\tCONNECTOR\t{BUILD}\tEXTERNAL\n")
        validate_inventory(rows, BUILD)
        self.assertIn("EXTENSION_NAME, EXTENSION_TYPE, EXTENSION_VERSION, SOURCE", INVENTORY_SQL)
        self.assertNotIn("LOAD_TIME", INVENTORY_SQL)
        self.assertNotIn("DESCRIPTION", INVENTORY_SQL)

    def test_missing_null_stale_duplicate_builtin_and_wrong_kind_are_rejected(self):
        good = f"ducklake\tCONNECTOR\t{BUILD}\tEXTERNAL"
        for text in ("", good + "\n" + good, good.replace(BUILD, "NULL"), good.replace(BUILD, "\\N"),
                     good.replace(BUILD, ""), good.replace(BUILD, "doris-ducklake-v1-" + "b" * 64),
                     good.replace("EXTERNAL", "BUILTIN"), good.replace("CONNECTOR", "FILESYSTEM"),
                     good.replace("ducklake", "DuckLake", 1)):
            with self.subTest(text=text), self.assertRaises(ValueError):
                validate_inventory(inventory_rows(text), BUILD)

    def test_malformed_raw_rows_cannot_be_silently_truncated(self):
        for text in ("ducklake\tCONNECTOR", f"ducklake\tCONNECTOR\t{BUILD}\tEXTERNAL\textra",
                     f"ducklake\tCONNECTOR\t{BUILD}\tEXTERNAL\n\n"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                inventory_rows(text)

    def test_source_columns_are_named_not_positional_and_dead_nodes_do_not_win(self):
        self.assertEqual(alive_versions("Host\tVersion\tAlive\nfe\tpinned\ttrue\nold\tstale\tfalse\n"), ["pinned"])
        self.assertEqual(alive_versions("Alive\tVersion\nfalse\tNULL\n"), [])
        self.assertEqual(alive_versions("Alive\tVersion\ntrue\tB\ntrue\tA\ntrue\tB\n"), ["A", "B"])

    def test_malformed_source_inventories_fail_closed(self):
        for text in ("", "IsAlive\tVersion\ntrue\tpinned", "Alive\tVersion\tVersion", "Alive\tVersion\ntrue",
                     "Alive\tVersion\nTRUE\tpinned", "Alive\tVersion\ntrue\tNULL"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                alive_versions(text)


class ReceiptBoundaryTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="attestation-receipts-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.deployment = Deployment(self.root / "compose.yml", {"target": {}}, self.root)

    def result(self):
        return json.loads((self.deployment.evidence / "run-result.json").read_text())

    def test_run_cannot_succeed_without_a_complete_attestation(self):
        with patch.object(self.deployment, "_run"), self.assertRaisesRegex(ValueError, "not completed"):
            self.deployment.run()
        self.assertFalse(self.result()["run_success"])

    def test_zero_exit_without_required_full_correctness_proof_is_not_success(self):
        self.deployment.mode = "full"
        self.deployment.state["mode"] = "full"
        with patch.object(self.deployment, "_run", side_effect=lambda _: self.deployment.state.update(deployment_attested=True)):
            with self.assertRaisesRegex(ValueError, "correctness proof"):
                self.deployment.run(["zero-exit-with-no-checks"])
        self.assertFalse(self.result()["run_success"])
        self.assertFalse(self.result()["smoke_correctness_passed"])

    def test_success_is_command_completion_not_f25_correctness(self):
        with patch.object(self.deployment, "_run", side_effect=lambda _: self.deployment.state.update(deployment_attested=True)):
            self.deployment.run(["a-command"])
        self.assertEqual(self.result()["status"], "command-completed")
        self.assertTrue(self.result()["deployment_attested"])
        self.assertFalse(self.result()["smoke_correctness_passed"])
        self.assertFalse(self.result()["f25_complete"])

    def test_error_before_attestation_never_creates_a_passing_receipt(self):
        with patch.object(self.deployment, "_run", side_effect=RuntimeError("identity refused")):
            with self.assertRaises(RuntimeError):
                self.deployment.run()
        self.assertFalse(self.result()["deployment_attested"])
        self.assertFalse((self.deployment.evidence / "deployment-attested.json").exists())

    def test_later_command_or_cleanup_error_retains_observation_but_no_current_passing_receipt(self):
        observation = {"status": "deployment-attested", "run_success": None, "smoke_correctness_passed": False}

        def failed(_):
            self.deployment.state["deployment_attested"] = True
            self.deployment.write_record("deployment-attested.json", observation)
            raise RuntimeError("later smoke or cleanup failed")

        with patch.object(self.deployment, "_run", side_effect=failed), self.assertRaises(RuntimeError):
            self.deployment.run(["failing-command"])
        self.assertFalse((self.deployment.evidence / "deployment-attested.json").exists())
        self.assertEqual(json.loads((self.deployment.evidence / "deployment-attested-before-error.json").read_text()), observation)
        self.assertFalse(self.result()["run_success"])
        self.assertTrue(self.result()["deployment_attested"])


if __name__ == "__main__":
    unittest.main()
