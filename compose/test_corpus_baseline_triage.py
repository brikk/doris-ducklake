import unittest

from corpus_baseline_triage import counterpart, triage


def baseline(kind, sql="SELECT 42"):
    return {"files": [{"path": "a.test", "outcomes": [{"line": 1, "kind": kind, "sql": sql, "reason": "native error"}]}],
            "missing_files": []}


class CorpusBaselineTriageTest(unittest.TestCase):
    def test_failing_native_control_does_not_become_a_doris_regression(self):
        rows = [{"file": "a.test", "line": "1", "stage": "oracle-or-harness-record", "reason": "row mismatch"}]
        result = triage(rows, baseline("Fail"), baseline("Fail"), baseline("Pass"), baseline("Pass"))
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["classification"], "native-or-replay-baseline")
        self.assertEqual(result[0]["reason"], "row mismatch")
        self.assertEqual(result[0]["producer_embedded"], "Pass")

    def test_mirror_failures_remain_failures_when_both_oracles_pass(self):
        rows = [{"file": "a.test", "line": "1", "stage": "doris-mirror-divergence", "reason": "wrong rows"}]
        self.assertEqual(triage(rows, baseline("Pass"), baseline("Pass"))[0]["classification"], "doris-value-divergence")

    def test_missing_and_ambiguous_counterparts_are_not_success(self):
        original = baseline("Fail")["files"][0]["outcomes"]
        self.assertEqual(counterpart(original, {"files": [], "missing_files": ["a.test"]}, "a.test"), "file-absent")
        self.assertEqual(counterpart(original, baseline("Pass", "SELECT 43"), "a.test"), "unmapped")
        duplicate = baseline("Pass")
        duplicate["files"][0]["outcomes"] *= 2
        self.assertEqual(counterpart(original, duplicate, "a.test"), "unmapped")

    def test_backend_differences_and_live_only_failures_stay_distinct(self):
        rows = [{"file": "a.test", "line": "1", "stage": "oracle-or-harness-record", "reason": "wrong count"}]
        self.assertEqual(triage(rows, baseline("Pass"), baseline("Fail"))[0]["classification"], "metadata-backend-axis")
        self.assertEqual(triage(rows, baseline("Pass"), baseline("Pass"))[0]["classification"], "live-only-or-nondeterministic")
        self.assertEqual(triage(rows, baseline("Pass"), baseline("Skip"))[0]["classification"], "unresolved-baseline-evidence")


if __name__ == "__main__":
    unittest.main()
