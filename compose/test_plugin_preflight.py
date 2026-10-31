import hashlib
import io
import json
from contextlib import redirect_stderr
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import plugin_preflight as preflight
from plugin_test_fixture import compose_target, write_plugin


class PluginArtifactTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="plugin-identity-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.archive = self.root / "doris-ducklake-test-plugin.zip"

    def verify(self):
        return preflight.verify_bundle(self.archive, self.archive.with_suffix(".identity.json"))

    def test_java_python_canonical_vector(self):
        a, b = "a" * 64, "b" * 64
        text = f"doris-plugin-payload-v1\nAPI\t24.0\nP\ta.class\t{a}\nP\tz.class\t{b}\nD\tlib/example.jar\t{a}\n"
        self.assertEqual(preflight.build_id("24.0", {"z.class": b, "a.class": a}, {"lib/example.jar": a}),
                         "doris-ducklake-v1-" + hashlib.sha256(text.encode()).hexdigest())

    def test_actual_bundle_and_folded_manifest_verify(self):
        expected = write_plugin(self.archive)
        self.assertEqual(self.verify(), expected)

    def test_changed_compiled_or_runtime_bytes_change_id_at_same_api(self):
        first = write_plugin(self.archive)
        second = write_plugin(self.archive, payload_value=b"compiled-B")
        third = write_plugin(self.archive, dependency=b"dependency-B")
        self.assertEqual(first["api_version"], second["api_version"])
        self.assertNotEqual(first["build_id"], second["build_id"])
        self.assertNotEqual(first["build_id"], third["build_id"])

    def test_matching_provider_package_version_is_allowed(self):
        expected = write_plugin(self.archive)
        write_plugin(self.archive, package_version=expected["build_id"])
        self.assertEqual(self.verify()["build_id"], expected["build_id"])

    def test_package_section_cannot_override_expected_identity(self):
        write_plugin(self.archive, package_version="stale-build")
        with self.assertRaisesRegex(ValueError, "package identity"):
            self.verify()

    def test_corrupt_archive_and_receipt_hashes_fail(self):
        write_plugin(self.archive)
        self.archive.write_bytes(self.archive.read_bytes() + b"unexpected")
        with self.assertRaisesRegex(ValueError, "ZIP checksum"):
            self.verify()

    def test_sidecar_build_and_sdk_identity_are_checked(self):
        for field in ("build_id", "compile_sdk_id", "provider_jar_sha256"):
            receipt = write_plugin(self.archive)
            receipt[field] = "wrong"
            self.archive.with_suffix(".identity.json").write_text(json.dumps(receipt))
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.verify()

    def test_api_major_is_not_an_arbitrary_override(self):
        write_plugin(self.archive, api="13.0")
        with self.assertRaisesRegex(ValueError, "schema/API"):
            self.verify()

    def test_malformed_receipt_object_and_duplicate_keys_are_rejected(self):
        write_plugin(self.archive)
        receipt = self.archive.with_suffix(".identity.json")
        for text in ("[]", '{"schema":"first","schema":"second"}'):
            receipt.write_text(text)
            with self.subTest(text=text), self.assertRaises(ValueError):
                self.verify()

    def test_selection_rejects_missing_and_ambiguous_candidates_regardless_of_mtime(self):
        with self.assertRaisesRegex(ValueError, "found 0"):
            preflight.select_zip(self.root)
        write_plugin(self.archive)
        other = self.root / "doris-ducklake-other-plugin.zip"
        write_plugin(other)
        with self.assertRaisesRegex(ValueError, "found 2"):
            preflight.select_zip(self.root)
        self.assertEqual(preflight.select_zip(self.root, self.archive), self.archive.resolve())
        with self.assertRaises(FileNotFoundError):
            preflight.select_zip(self.root, self.root / "absent.zip")

    def test_unreadable_copy_fails_before_docker_and_removes_only_private_staging(self):
        write_plugin(self.archive)
        original = self.archive.read_bytes()
        stage = self.root / "frozen"
        args = ["plugin_preflight.py", "--distributions", str(self.root), "--plugin-zip", str(self.archive),
                "--compose-file", str(self.root / "compose.yml"), "--staging-root", str(stage)]
        with patch("sys.argv", args), patch.object(preflight.shutil, "copyfile", side_effect=PermissionError("unreadable input")), \
                patch.object(preflight, "docker_output") as docker, redirect_stderr(io.StringIO()) as stderr:
            self.assertEqual(preflight.main(), 1)
        docker.assert_not_called()
        self.assertIn("unreadable input", stderr.getvalue())
        self.assertEqual(list(stage.iterdir()), [])
        self.assertEqual(self.archive.read_bytes(), original)


class PluginTargetTests(unittest.TestCase):
    def target(self, config=None, container_labels=None, volume_labels=None, mounted_volume=None, consumers=None,
               existing=True, volume=True, host_config=None, network_settings=None):
        config = config or compose_target()
        volume_name = config["volumes"]["fe-plugin-ducklake"]["name"]
        labels = {"com.docker.compose.project": config["name"], "com.docker.compose.service": "doris-fe"}
        labels.update(container_labels or {})
        owned_volume = {"com.docker.compose.project": config["name"], "com.docker.compose.volume": "fe-plugin-ducklake"}
        if volume_labels is not None:
            owned_volume = volume_labels
        commands = []

        def output(*args):
            commands.append(args)
            if args[0] == "compose":
                return json.dumps(config)
            if args[:2] == ("container", "ls"):
                if any(a.startswith("volume=") for a in args):
                    return "\n".join(consumers if consumers is not None else (["owned-fe"] if existing else []))
                if any("doris-ducklake-be" in a for a in args):
                    return ""
                return "owned-fe" if existing else ""
            if args[:2] == ("container", "inspect"):
                return json.dumps([{"Name": "/" + config["services"]["doris-fe"]["container_name"],
                                    "Config": {"Labels": labels}, "Mounts": [{"Type": "volume",
                                    "Name": mounted_volume or volume_name, "Destination": preflight.PLUGIN_MOUNT}],
                                    "HostConfig": host_config if host_config is not None else {
                                        "NetworkMode": "owned-default", "PublishAllPorts": False, "PortBindings": {}},
                                    "NetworkSettings": network_settings if network_settings is not None else {"Ports": {}}}])
            if args[:2] == ("volume", "ls"):
                return volume_name if volume else ""
            if args[:2] == ("volume", "inspect"):
                return json.dumps([{"Labels": owned_volume}])
            raise AssertionError(args)

        with patch.object(preflight, "docker_output", side_effect=output):
            result = preflight.target_from_compose(Path("compose.yml"))
        self.assertTrue(all(a[0] == "compose" or a[1] in {"ls", "inspect"} for a in commands))
        return result

    def test_uses_rendered_image_and_volume_not_hardcoded_defaults(self):
        config = compose_target()
        config["services"]["doris-fe"]["image"] = "example/fe:explicit"
        config["volumes"]["fe-plugin-ducklake"]["name"] = "explicit-plugin-volume"
        result = self.target(config)
        self.assertEqual(result["fe_image"], "example/fe:explicit")
        self.assertEqual(result["plugin_volume"], "explicit-plugin-volume")

    def test_fresh_owned_target_needs_no_container_or_volume_creation(self):
        result = self.target(existing=False, volume=False)
        self.assertFalse(result["fe_present"])
        self.assertFalse(result["volume_present"])

    def test_foreign_container_project_or_service_is_rejected(self):
        for labels in ({"com.docker.compose.project": "another-run"}, {"com.docker.compose.service": "other"}):
            with self.subTest(labels=labels), self.assertRaisesRegex(ValueError, "Foreign FE"):
                self.target(container_labels=labels)

    def test_foreign_or_mis_mounted_volume_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "different plugin volume"):
            self.target(mounted_volume="another-run")
        with self.assertRaisesRegex(ValueError, "Foreign plugin volume"):
            self.target(volume_labels={"com.docker.compose.project": "another-run"})

    def test_foreign_consumer_and_unlabelled_orphan_volume_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "foreign container"):
            self.target(consumers=["owned-fe", "another-fe"])
        with self.assertRaisesRegex(ValueError, "unproven"):
            self.target(existing=False, volume_labels={})
        self.assertTrue(self.target(volume_labels={})["fe_present"])

    def test_public_or_internal_compose_ports_are_refused_before_container_lookup(self):
        for port in (
            {"target": 9030, "published": "9030"},
            {"target": 9030, "published": "9030", "host_ip": "0.0.0.0"},
            {"target": 8030, "published": "8030", "host_ip": "::"},
            {"target": 8030, "published": "8030", "host_ip": "192.0.2.1"},
            {"target": 9020, "published": "9020", "host_ip": "127.0.0.1"},
            {"target": 9010, "published": "9010", "host_ip": "127.0.0.1"},
            {"target": 9030, "published": "0", "host_ip": "127.0.0.1"},
            {"target": 9030, "published": "9030", "host_ip": "127.0.0.1", "protocol": "udp"},
        ):
            config = compose_target()
            config["services"]["doris-fe"]["ports"] = [port]
            with self.subTest(port=port), self.assertRaisesRegex(ValueError, "F23"):
                self.target(config, existing=False, volume=False)

    def test_old_public_container_is_refused_even_with_safe_compose(self):
        for host in ("", "0.0.0.0", "::", "192.0.2.1"):
            requested = {"9030/tcp": [{"HostIp": host, "HostPort": "9030"}]}
            with self.subTest(host=host), self.assertRaisesRegex(ValueError, "F23"):
                self.target(host_config={"NetworkMode": "owned-default", "PublishAllPorts": False, "PortBindings": requested})

    def test_effective_bindings_are_checked_independently_of_requested_bindings(self):
        public = {"9030/tcp": [{"HostIp": "0.0.0.0", "HostPort": "9030"}]}
        with self.assertRaisesRegex(ValueError, "F23"):
            self.target(network_settings={"Ports": public})

    def test_no_publication_and_explicit_ipv4_ipv6_loopback_are_allowed(self):
        for host in ("127.0.0.1", "::1"):
            config = compose_target()
            config["services"]["doris-fe"]["ports"] = [
                {"target": 9030, "published": "19030", "host_ip": host, "protocol": "tcp"}]
            bindings = {"9030/tcp": [{"HostIp": host, "HostPort": "19030"}], "9010/tcp": None}
            result = self.target(config, host_config={"NetworkMode": "owned-default", "PublishAllPorts": False,
                                                     "PortBindings": bindings}, network_settings={"Ports": bindings})
            self.assertTrue(result["fe_present"])

    def test_host_or_shared_namespace_and_publish_all_are_not_a_bypass(self):
        for mode in ("host", "container:other", "service:doris-be"):
            config = compose_target()
            config["services"]["doris-fe"]["network_mode"] = mode
            with self.subTest(mode=mode), self.assertRaisesRegex(ValueError, "F23"):
                self.target(config, existing=False, volume=False)
            with self.subTest(inspected=mode), self.assertRaisesRegex(ValueError, "F23"):
                self.target(host_config={"NetworkMode": mode, "PublishAllPorts": False, "PortBindings": {}})
        with self.assertRaisesRegex(ValueError, "F23"):
            self.target(host_config={"NetworkMode": "owned-default", "PublishAllPorts": True, "PortBindings": {}})

    def test_missing_inspection_metadata_fails_closed(self):
        for values in ({"host_config": {}}, {"network_settings": {}}):
            with self.subTest(values=values), self.assertRaisesRegex(ValueError, "F23"):
                self.target(**values)

    def test_backend_ports_cannot_publish_internal_services(self):
        config = compose_target()
        config["services"]["doris-be"]["ports"] = [{"target": 8040, "published": "8040", "host_ip": "127.0.0.1"}]
        with self.assertRaisesRegex(ValueError, "F23"):
            self.target(config, existing=False, volume=False)


if __name__ == "__main__":
    unittest.main()
