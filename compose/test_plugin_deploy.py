"""Claim ownership and conservative cleanup regressions; no Docker access."""

import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from plugin_deploy import Deployment, OWNER, claim_name, process_record
from plugin_preflight import file_hash


class ClaimDaemon:
    def __init__(self):
        self.instances = {}
        self.calls = []
        self.unstoppable = set()

    def inspect(self, name):
        return copy.deepcopy(self.instances[name])

    def docker(self, *args, **_kwargs):
        self.calls.append(args)
        if args[0] == "create":
            name = args[args.index("--name") + 1]
            if name in self.instances:
                raise RuntimeError("occupied")
            labels = dict(args[i + 1].split("=", 1) for i, v in enumerate(args[:-1]) if v == "--label")
            self.instances[name] = {"Id": name, "Config": {"Labels": labels}, "State": {"Running": False}}
            return name
        if args[0] == "start":
            self.instances[args[1]]["State"]["Running"] = True
        elif args[0] == "stop":
            if args[-1] in self.unstoppable:
                raise RuntimeError("cannot confirm helper stop")
            self.instances[args[-1]]["State"]["Running"] = False
        elif args[0] == "rm":
            del self.instances[args[1]]
        else:
            raise AssertionError(args)
        return ""


class DeploymentOwnershipTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="deploy-claims-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.daemon = ClaimDaemon()
        for name, value in (("docker", self.daemon.docker), ("inspect", self.daemon.inspect)):
            patcher = patch("plugin_deploy." + name, side_effect=value)
            patcher.start()
            self.addCleanup(patcher.stop)
        patcher = patch("plugin_deploy.docker_json", return_value=[{"Id": "local-helper-image"}])
        patcher.start()
        self.addCleanup(patcher.stop)

    def deployment(self, checkout="first", volume="same-daemon-volume"):
        return Deployment(self.root / checkout / "compose.yml", {"target": {"plugin_volume": volume}}, self.root / checkout)

    def test_same_daemon_volume_claim_is_shared_across_checkouts_and_never_stolen(self):
        first, second = self.deployment(), self.deployment("other-checkout")
        with first.ownership():
            self.assertEqual(first.claim, claim_name("same-daemon-volume"))
            with self.assertRaisesRegex(RuntimeError, "occupied"):
                with second.ownership():
                    self.fail("second owner entered")
            self.assertTrue(self.daemon.instances[first.claim]["State"]["Running"])
        self.assertFalse(self.daemon.instances)
        self.assertFalse(any("--force" in a or "-f" in a for a in self.daemon.calls))

    def test_independent_volume_can_be_claimed_without_global_lock(self):
        first, second = self.deployment(), self.deployment("second", volume="other-volume")
        with first.ownership(), second.ownership():
            self.assertEqual(len(self.daemon.instances), 2)
        self.assertFalse(self.daemon.instances)

    def test_exception_releases_only_this_invocations_claim(self):
        first = self.deployment()
        with self.assertRaisesRegex(RuntimeError, "interrupted"):
            with first.ownership():
                raise RuntimeError("interrupted")
        self.assertFalse(self.daemon.instances)
        self.assertEqual(first.state["events"][-1]["event"], "claim-released")

    def test_changed_owner_token_is_never_removed(self):
        first = self.deployment()
        with self.assertRaisesRegex(ValueError, "owner token changed"):
            with first.ownership():
                self.daemon.instances[first.claim]["Config"]["Labels"][OWNER] = "not-our-owner"
        self.assertIn(first.claim, self.daemon.instances)
        self.assertFalse(any(a[0] == "rm" for a in self.daemon.calls))

    def test_unconfirmed_worker_stop_retains_claim_and_blocks_a_new_deployer(self):
        first = self.deployment()
        with first.ownership():
            helper = "owned-worker"
            self.daemon.instances[helper] = {"Id": helper, "Config": {"Labels": {OWNER: first.token}},
                                             "State": {"Running": True}}
            first.helpers.append(helper)
            self.daemon.unstoppable.add(helper)
        self.assertIn(first.claim, self.daemon.instances)
        self.assertEqual(first.state["events"][-1]["event"], "claim-retained-for-recovery")
        with self.assertRaisesRegex(RuntimeError, "occupied"):
            with self.deployment("rival").ownership():
                self.fail("rival entered")

    def test_unconfirmed_failed_fe_stop_retains_claim(self):
        first = self.deployment()
        with first.ownership():
            first.install_boundary = True
            with patch.object(first, "fe", side_effect=ValueError("foreign FE appeared")):
                first.fail_closed()
        self.assertTrue(first.state["unsafe_fe_state"])
        self.assertIn(first.claim, self.daemon.instances)

    def test_target_config_change_is_rejected_during_revalidation(self):
        first = self.deployment()
        first.target.update({k: k for k in ("project", "fe_service", "fe_container", "plugin_volume_key", "fe_image")})
        first.target["compose_sha256"] = "before"
        current = dict(first.target, compose_sha256="after")
        with patch("plugin_deploy.target_from_compose", return_value=current):
            with self.assertRaisesRegex(ValueError, "compose configuration changed"):
                first.revalidate()

    def test_mount_identity_ignores_docker_list_order_but_not_changed_persistent_targets(self):
        instance = {"Id": "same-id", "Image": "same-image", "Config": {"Hostname": "same-fe"},
                    "State": {"StartedAt": "same-start", "Running": False}, "Mounts": [
                        {"Type": "volume", "Name": "metadata", "Destination": "/metadata", "RW": True},
                        {"Type": "volume", "Name": "plugin", "Destination": "/plugin", "RW": True}]}
        original = process_record(instance)
        instance["Mounts"].reverse()
        self.assertEqual(process_record(instance), original)
        instance["Mounts"][0]["Name"] = "wrong-volume"
        self.assertNotEqual(process_record(instance), original)


class RuntimeConfigInitializationTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="runtime-config-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.runtime = self.root / ".fe.conf.runtime"
        self.original = b"heap = fixed\npriority_networks = 198.18.16.0/24\n"
        self.runtime.write_bytes(self.original)
        self.deployment = Deployment(self.root / "compose.yml", {"target": {}}, self.root / "evidence")
        self.deployment.stage_config()

    def test_exact_same_value_first_boot_append_is_recorded_and_then_frozen(self):
        self.runtime.write_bytes(self.original + b"priority_networks = 198.18.16.0/24\n")
        self.deployment.verify_runtime_config()
        self.assertEqual(self.deployment.state["runtime_config_sha256"], file_hash(self.runtime))
        self.assertTrue(self.deployment.state["runtime_config_initialized"])
        self.assertEqual(self.deployment.state["events"][-1]["event"], "first-boot-runtime-config-initialized")
        self.deployment.verify_runtime_config()  # Stable bytes are idempotent.
        self.runtime.write_bytes(self.runtime.read_bytes() + b"priority_networks = 198.18.16.0/24\n")
        with self.assertRaisesRegex(ValueError, "configuration changed"):
            self.deployment.verify_runtime_config()

    def test_previously_started_container_cannot_change_even_the_same_network_line(self):
        self.deployment.prior = {"started_at": "2026-10-29T12:00:00Z"}
        self.runtime.write_bytes(self.original + b"priority_networks = 198.18.16.0/24\n")
        with self.assertRaisesRegex(ValueError, "configuration changed"):
            self.deployment.verify_runtime_config()

    def test_created_but_never_started_container_has_the_same_first_boot_rule(self):
        self.deployment.prior = {"started_at": "0001-01-01T00:00:00Z"}
        self.runtime.write_bytes(self.original + b"priority_networks = 198.18.16.0/24\n")
        self.deployment.verify_runtime_config()
        self.assertTrue(self.deployment.state["runtime_config_initialized"])

    def test_first_boot_cannot_change_network_or_any_other_config_bytes(self):
        for content in (self.original + b"priority_networks = 198.18.17.0/24\n",
                        self.original.replace(b"heap = fixed", b"heap = different"),
                        self.original + b"new_setting = enabled\n"):
            with self.subTest(content=content):
                self.runtime.write_bytes(content)
                with self.assertRaisesRegex(ValueError, "configuration changed"):
                    self.deployment.verify_runtime_config()


if __name__ == "__main__":
    unittest.main()
