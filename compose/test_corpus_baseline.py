"""Diagnostics must preserve record failures and distinguish missing/blocked files from passes."""
import json
from pathlib import Path
import tempfile
import unittest

from corpus_baseline import baseline_measurements, selection_from_failures


class CorpusBaselineTest(unittest.TestCase):
    def test_selection_is_deduplicated_but_never_escapes_the_snapshot(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "failures.csv"
            path.write_text("file,line\na.test,1\na.test,2\nb.test_slow,9\n")
            self.assertEqual(selection_from_failures(path), ["a.test", "b.test_slow"])
            for name in ("../a.test", "/a.test", "a.txt"):
                path.write_text("file,line\n" + name + ",1\n")
                with self.assertRaises(ValueError):
                    selection_from_failures(path)

    def measure(self, **changes):
        receipt = {"schema": "doris-oracle-baseline-v1", "doris_mirrors": 0, "selected": ["a.test", "b.test"],
                   "missing_files": ["b.test"], "failed": 1, "passed": 2, "skipped_records": 0, "ran_files": 1,
                   "files": [{"path": "a.test", "file_skip_reason": None, "outcomes": [{"kind": "Fail"}]}]}
        receipt.update(changes)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "baseline.json"
            path.write_text(json.dumps(receipt))
            return baseline_measurements(path)

    def test_missing_and_failed_are_explicit_not_counted_as_passes(self):
        result = self.measure()
        self.assertEqual(result["missing"], ["b.test"])
        self.assertEqual(result["failed_records"], 1)
        self.assertEqual(result["present"], 1)

    def test_incomplete_coverage_failure_loss_and_mirror_claims_refuse(self):
        for change in ({"missing_files": []}, {"missing_files": ["a.test", "b.test"]}, {"failed": 0}, {"doris_mirrors": 1}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.measure(**change)

    def test_a_timeout_is_not_a_completed_diagnostic(self):
        files = [{"path": "a.test", "file_skip_reason": "TIMEOUT: quarantined", "outcomes": [{"kind": "Fail"}]}]
        self.assertEqual(self.measure(files=files)["incomplete_files"], ["a.test"])

    def test_all_missing_or_unexecuted_files_cannot_be_a_baseline(self):
        for files, missing in (([], ["a.test", "b.test"]),
                               ([{"path": "a.test", "outcomes": [], "file_skip_reason": "require missing"}], ["b.test"])):
            with self.subTest(files=files), self.assertRaisesRegex(ValueError, "No executed oracle records"):
                self.measure(files=files, missing_files=missing, failed=0)


if __name__ == "__main__":
    unittest.main()
