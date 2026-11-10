"""Release acceptance refuses old/mixed catalog bundles before infrastructure work."""
import unittest
from unittest.mock import patch

from read_correctness_acceptance import ReadCorrectnessAcceptance
from smoke_acceptance import FullSmokeAcceptance


class CatalogReleaseAcceptanceTest(unittest.TestCase):
    def fixture(self, a="0.18.0", b="0.18.0"):
        fixture = object.__new__(ReadCorrectnessAcceptance)
        fixture.artifacts = {key: {"files": {f"lib/ducklake-catalog-{version}.jar": "digest"}}
                             for key, version in (("A", a), ("B", b))}
        fixture.result = {}
        return fixture

    def test_release_runs_quoted_nested_and_measured_replay_on_both_jvms(self):
        fixture = self.fixture()
        fixture.require_released_bundles()
        self.assertEqual(fixture.result["lane"], "released-catalog-0.18.0")
        self.assertEqual(fixture.seed_script, "read_correctness_seed.py")
        self.assertEqual(fixture.live_tasks, ("readCorrectnessLiveTest", "readCorrectnessLiveJava17Test"))
        self.assertEqual(fixture.live_test_count, 2)

    def test_old_or_mixed_catalog_refuses_before_prepare(self):
        for a, b in (("0.17.0", "0.18.0"), ("0.18.0", "0.17.0"), ("0.18.0-SNAPSHOT", "0.18.0")):
            with self.subTest(a=a, b=b), patch.object(FullSmokeAcceptance, "prepare") as prepare:
                with self.assertRaisesRegex(ValueError, "Not a default catalog0.18.0 bundle"):
                    self.fixture(a, b).prepare()
                prepare.assert_not_called()

    def test_extra_catalog_refuses(self):
        fixture = self.fixture()
        fixture.artifacts["A"]["files"]["lib/ducklake-catalog-0.12.0.jar"] = "old"
        with patch.object(FullSmokeAcceptance, "prepare") as prepare:
            with self.assertRaisesRegex(ValueError, "Not a default catalog0.18.0 bundle"):
                fixture.prepare()
            prepare.assert_not_called()


if __name__ == "__main__":
    unittest.main()
