"""F23 network containment policy and real Compose-render regressions."""

import copy
import json
import os
from pathlib import Path
import subprocess
import unittest

from plugin_preflight import development_compose_network, development_container_network
from plugin_test_fixture import compose_target


class DevelopmentNetworkTests(unittest.TestCase):
    def test_inspected_backend_cannot_publish_any_port_or_share_host_network(self):
        safe = {"HostConfig": {"NetworkMode": "owned-bridge", "PublishAllPorts": False, "PortBindings": {}},
                "NetworkSettings": {"Ports": {"8040/tcp": None}}}
        development_container_network(safe, "doris-be")
        for field in ("HostConfig", "NetworkSettings"):
            bad = copy.deepcopy(safe)
            key = "PortBindings" if field == "HostConfig" else "Ports"
            bad[field][key] = {"8040/tcp": [{"HostIp": "127.0.0.1", "HostPort": "18040"}]}
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "F23"):
                development_container_network(bad, "doris-be")
        bad = copy.deepcopy(safe)
        bad["HostConfig"]["NetworkMode"] = "host"
        with self.assertRaisesRegex(ValueError, "F23"):
            development_container_network(bad, "doris-be")

    def test_external_or_explicit_host_driver_is_not_a_compose_bypass(self):
        for network in ({"name": "host", "external": True}, {"name": "other", "driver": "host"}):
            config = compose_target()
            config["networks"]["shared"] = network
            with self.subTest(network=network), self.assertRaisesRegex(ValueError, "F23"):
                development_compose_network(config)

    def test_malformed_ports_or_missing_host_metadata_cannot_pass(self):
        safe = {"HostConfig": {"NetworkMode": "owned-bridge", "PublishAllPorts": False, "PortBindings": {}},
                "NetworkSettings": {"Ports": {}}}
        for bindings in ({"9030/tcp": [{"HostPort": "9030"}]}, {"9030/udp": [{"HostIp": "127.0.0.1", "HostPort": "9030"}]},
                         {"9030/tcp": [{"HostIp": "127.0.0.1", "HostPort": "0"}]}, {"9030/tcp": "malformed"}):
            bad = copy.deepcopy(safe)
            bad["HostConfig"]["PortBindings"] = bindings
            with self.subTest(bindings=bindings), self.assertRaisesRegex(ValueError, "F23"):
                development_container_network(bad, "doris-fe")


@unittest.skipUnless(os.environ.get("F24_DOCKER_TEST") == "1", "opt-in real Compose rendering")
class RenderedDevelopmentNetworkTests(unittest.TestCase):
    def test_actual_default_and_r07_composes_are_loopback_only_without_internal_publications(self):
        root = Path(__file__).resolve().parent
        for name in ("docker-compose.yml", "r07-qualification.yml"):
            with self.subTest(compose=name):
                config = json.loads(subprocess.check_output(
                    ["docker", "compose", "-f", str(root / name), "config", "--format", "json"], text=True,
                    env=dict(os.environ, DORIS_R07_ROOT="/tmp/opencode/f23-render-only")))
                if name == "r07-qualification.yml":
                    config["services"]["doris-fe"] = config["services"].pop("fe")
                    config["services"]["doris-be"] = config["services"].pop("be")
                development_compose_network(config)
                ports = config["services"]["doris-fe"]["ports"]
                self.assertEqual({(p["target"], p["host_ip"], p["protocol"]) for p in ports},
                                 {(8030, "127.0.0.1", "tcp"), (9030, "127.0.0.1", "tcp")})
                self.assertEqual(len(ports), 2)
                self.assertFalse(config["services"]["doris-be"].get("ports"))


if __name__ == "__main__":
    unittest.main()
