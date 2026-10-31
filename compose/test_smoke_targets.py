import json
from pathlib import Path
from types import SimpleNamespace
import unittest

from plugin_preflight import sha256
from plugin_test_fixture import compose_target, substrate_target
from smoke_targets import resolve_substrate


class SmokeTargetTests(unittest.TestCase):
    def resolve(self, *, foreign=False, wrong_network=False, stopped=False, changed=False):
        doris = compose_target()
        substrate = substrate_target(custom=True)
        doris["networks"]["shared"]["name"] = "owned-network"
        digest = sha256(json.dumps(doris, sort_keys=True, separators=(",", ":")).encode())
        if changed:
            doris["services"]["doris-fe"]["hostname"] = "changed"
        if wrong_network:
            substrate["networks"]["default"]["name"] = "unrelated"
        deployment = SimpleNamespace(compose=Path("doris.yml"), target={"compose_sha256": digest})

        def docker(*args):
            if args[0] == "compose":
                return json.dumps(doris if args[2] == deployment.compose else substrate)
            if args[:2] == ("image", "inspect"):
                return json.dumps([{"Id": args[2] + "-immutable-id"}])
            if args[:2] == ("container", "ls"):
                return "pg" if any("owned-pg" in arg for arg in args) else "minio"
            self.fail(args)

        def inspect(identifier):
            service = "postgres" if identifier == "pg" else "minio"
            selected = substrate["services"][service]
            return {"Name": "/" + selected["container_name"], "Image": selected["image"] + "-immutable-id",
                    "State": {"Running": not stopped}, "Config": {"Labels": {
                        "com.docker.compose.project": "foreign" if foreign else substrate["name"],
                        "com.docker.compose.service": service}}}

        return resolve_substrate(deployment, Path("substrate.yml"), docker, inspect, running=True)

    def test_public_routing_is_bound_but_secrets_only_enter_child_environment(self):
        public, private = self.resolve()
        self.assertEqual(public["postgres"], "owned-pg")
        self.assertEqual(public["minio"], "owned-minio")
        self.assertEqual(public["network"], "owned-network")
        self.assertNotIn("fixture-pg-secret", json.dumps(public))
        self.assertNotIn("fixture-s3-secret", json.dumps(public))
        self.assertEqual(private["SMOKE_PG_PASSWORD"], "fixture-pg-secret")
        self.assertEqual(private["SMOKE_S3_SECRET"], "fixture-s3-secret")

    def test_foreign_changed_unconnected_or_stopped_targets_fail(self):
        for options in ({"foreign": True}, {"changed": True}, {"wrong_network": True}, {"stopped": True}):
            with self.subTest(options=options), self.assertRaises(ValueError):
                self.resolve(**options)


if __name__ == "__main__":
    unittest.main()
