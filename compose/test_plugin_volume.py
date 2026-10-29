"""The real replacement worker, using isolated directories (no Docker)."""

import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from plugin_test_fixture import write_plugin
from plugin_preflight import file_hash
from plugin_volume import install, inventory, prepare, snapshot, verify_backup, verify_installed


class PluginVolumeTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="plugin-volume-")
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.root = self.directory / "volume"
        self.work = self.directory / "work"
        self.root.mkdir()
        self.work.mkdir()
        self.artifact = write_plugin(self.work / "plugin.zip")

    def old_install(self):
        (self.root / "lib").mkdir()
        (self.root / "lib/stale.jar").write_bytes(b"previous-provider-and-dependency")
        (self.root / ".previous-manifest.json").write_text('{"identity":"legacy"}')
        (self.root / "empty-dir").mkdir()

    def test_backup_preserves_complete_old_files_manifest_directories_and_modes(self):
        self.old_install()
        (self.root / "lib/stale.jar").chmod(0o640)
        before = inventory(self.root)
        snapshot(self.root, self.work)
        receipt = json.loads((self.work / "previous.json").read_text())
        verify_backup(self.work / "previous.tar", receipt)
        self.assertEqual(receipt["files"], before["files"])
        self.assertEqual(receipt["directories"], before["directories"])
        self.assertEqual(inventory(self.root), before)

    def test_valid_replacement_removes_stale_and_hidden_entries_and_verifies_exact_set(self):
        self.old_install()
        snapshot(self.root, self.work)
        old_archive = (self.work / "previous.tar").read_bytes()
        install(self.root, self.work)
        actual = verify_installed(self.root, self.artifact)
        self.assertEqual(set(actual["files"]), set(self.artifact["files"]))
        self.assertEqual((self.work / "previous.tar").read_bytes(), old_archive)
        self.assertFalse((self.root / ".previous-manifest.json").exists())

    def test_corrupt_or_conflicting_candidate_never_changes_previous_install(self):
        self.old_install()
        snapshot(self.root, self.work)
        before = inventory(self.root)
        for corrupt in (True, False):
            if corrupt:
                (self.work / "plugin.zip").write_bytes(b"corrupt")
            else:
                write_plugin(self.work / "plugin.zip", package_version="conflicting-provider")
            with self.subTest(corrupt=corrupt), self.assertRaises(ValueError):
                install(self.root, self.work)
            self.assertEqual(inventory(self.root), before)

    def test_changed_old_volume_aborts_before_delete_and_retains_backup(self):
        self.old_install()
        snapshot(self.root, self.work)
        (self.root / "lib/stale.jar").write_bytes(b"changed-after-backup")
        before = inventory(self.root)
        with self.assertRaisesRegex(ValueError, "differs from preserved"):
            install(self.root, self.work)
        self.assertEqual(inventory(self.root), before)

    def test_links_special_files_and_tampered_backup_are_rejected(self):
        (self.root / "outside-link").symlink_to(self.directory / "work", target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "Nonregular"):
            snapshot(self.root, self.work)
        (self.root / "outside-link").unlink()
        snapshot(self.root, self.work)
        receipt = json.loads((self.work / "previous.json").read_text())
        (self.work / "previous.tar").write_bytes(b"corrupt")
        with self.assertRaisesRegex(ValueError, "checksum"):
            verify_backup(self.work / "previous.tar", receipt)

    def test_backup_detects_change_during_archive_creation(self):
        self.old_install()
        before = inventory(self.root)
        with patch("plugin_volume.inventory", side_effect=[before, {"files": {}, "directories": {}}]):
            with self.assertRaisesRegex(ValueError, "changed during backup"):
                snapshot(self.root, self.work)
        self.assertFalse((self.work / "previous.json").exists())

    def test_post_install_verification_rejects_extra_missing_changed_or_unreadable_files(self):
        snapshot(self.root, self.work)
        install(self.root, self.work)
        extra = self.root / "lib/extra.jar"
        extra.write_bytes(b"extra")
        with self.assertRaisesRegex(ValueError, "file set"):
            verify_installed(self.root, self.artifact)
        extra.unlink()
        provider = self.root / self.artifact["provider_jar"]
        provider.chmod(0o600)
        with self.assertRaisesRegex(ValueError, "Unreadable"):
            verify_installed(self.root, self.artifact)
        provider.chmod(0o644)
        provider.write_bytes(b"wrong")
        with self.assertRaisesRegex(ValueError, "file set"):
            verify_installed(self.root, self.artifact)
        provider.unlink()
        with self.assertRaisesRegex(ValueError, "file set"):
            verify_installed(self.root, self.artifact)

    def test_mid_install_failure_preserves_recoverable_archive_and_never_reports_success(self):
        self.old_install()
        snapshot(self.root, self.work)
        old_hash = file_hash(self.work / "previous.tar")
        with patch("plugin_volume.shutil.copytree", side_effect=OSError("injected disk failure")):
            with self.assertRaisesRegex(OSError, "disk failure"):
                install(self.root, self.work)
        self.assertEqual(file_hash(self.work / "previous.tar"), old_hash)
        self.assertFalse((self.work / "installed.json").exists())


if __name__ == "__main__":
    unittest.main()
