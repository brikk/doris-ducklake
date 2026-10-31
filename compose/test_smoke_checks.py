"""Required assertion completeness, typed comparison and publication regressions."""

import copy
import json
from pathlib import Path
import tempfile
import unittest

import smoke_checks as checks


class SmokeChecksTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="smoke-checks-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.path = self.root / "lifecycle.json"
        self.state = {"owner": "a" * 32, "artifact": {"build_id": "build-A"}, "mode": "full",
                      "deployment_attested": True, "loaded_provider_verified": True,
                      "qualification": "matching-pinned-fe-be", "runtime_sources": {"fe": ["pinned"], "be": ["pinned"]},
                      "smoke_target": {"project": "owned", "postgres": "owned-pg", "minio": "owned-minio", "network": "owned-net"}}
        self.path.write_text(json.dumps(self.state))
        checks.initialize(self.path, "b" * 32)

    def populate(self):
        for case in checks.REQUIRED:
            if case in {"tpch_sample", "delete_rows", "filter_rows"}:
                expected = [[i, "O"] for i in range(93 if case == "delete_rows" else 5)]
                operator, actual = "eq", copy.deepcopy(expected)
            else:
                operator, expected = checks.POLICY[case]
                actual = copy.deepcopy(expected)
                if operator == "expiry":
                    actual = {"expired": 2, "before": 3, "after": 1}
                elif operator == "cleanup":
                    actual = {"deleted": 1, "failed": 0, "blob": "gone", "schedule": 0}
                elif operator == "orphans":
                    actual = {"deleted": 1, "failed": 0, "orphan": "gone", "success": "present", "foreign": "present"}
            checks.record(self.path, case, operator, expected, actual)

    def test_complete_exact_ledger_is_the_only_passing_proof(self):
        self.populate()
        checks.finish(self.path)
        proof = checks.verify_completed(self.path)
        self.assertTrue(proof["smoke_correctness_passed"])
        self.assertTrue(proof["f25_complete"])
        self.assertEqual(proof["checks_passed"], len(checks.REQUIRED))
        self.assertEqual(proof["run_id"], "b" * 32)

    def test_missing_check_failed_check_and_abort_cannot_finish(self):
        with self.assertRaisesRegex(ValueError, "Missing/duplicate"):
            checks.finish(self.path)
        with self.assertRaisesRegex(ValueError, "failed"):
            checks.record(self.path, "nullable_counts", "eq", [4, 2], [4, 4])
        with self.assertRaisesRegex(ValueError, "Failed/aborted"):
            checks.finish(self.path)
        self.assertFalse((self.root / "smoke-correctness.json").exists())

    def test_duplicate_unknown_or_changed_expectation_is_rejected(self):
        checks.record(self.path, "w2_files", "ge", 1, 1)
        for case, operator, expected, actual in (("w2_files", "ge", 1, 2), ("unknown", "eq", 0, 0),
                                                  ("tpch_counts", "eq", [3, 3], [3, 3])):
            with self.subTest(case=case), self.assertRaises(ValueError):
                checks.record(self.path, case, operator, expected, actual)

    def test_receipt_tampering_owner_build_scope_hash_or_checks_fails(self):
        self.populate()
        checks.finish(self.path)
        file = self.root / "smoke-correctness.json"
        original = json.loads(file.read_text())
        for field, value in (("owner", "foreign"), ("build_id", "build-B"), ("scope", "other"),
                             ("checks_passed", 0), ("ledger_sha256", "wrong"), ("smoke_correctness_passed", False)):
            with self.subTest(field=field):
                altered = dict(original, **{field: value})
                file.write_text(json.dumps(altered))
                with self.assertRaises(ValueError):
                    checks.verify_completed(self.path)
        file.write_text(json.dumps(original))
        ledger_file = self.root / "smoke-assertions.json"
        ledger = json.loads(ledger_file.read_text())
        ledger["checks"][0]["expected"] = []
        ledger_file.write_text(json.dumps(ledger))
        with self.assertRaises(ValueError):
            checks.verify_completed(self.path)

    def test_later_abort_invalidates_previously_finished_checks(self):
        self.populate()
        checks.finish(self.path)
        checks.abort(self.path, "cleanup", 1)
        with self.assertRaisesRegex(ValueError, "completed"):
            checks.verify_completed(self.path)

    def test_typed_rows_reject_bool_float_string_or_same_count_wrong_values(self):
        for actual in ([[True, "alice"], [2, "bob"], [3, "charlie"]],
                       [[1.0, "alice"], [2, "bob"], [3, "charlie"]],
                       [["1", "alice"], [2, "bob"], [3, "charlie"]],
                       [[1, "alice"], [2, "CORRUPTED"], [3, "charlie"]]):
            self.assertFalse(checks.exact(actual, checks.THREE))

    def test_no_current_wildcard_xfail_and_exact_version_observation_matching(self):
        self.assertEqual(checks.KNOWN_FAILURES, ())
        row = {"case": "nullable_counts", "runtime_sources": {"fe": ["old"], "be": ["old"]},
               "actual": [4, 4], "issue": "reviewed-old-only"}
        self.assertIsNone(checks.known_failure("nullable_counts", [4, 4], self.state["runtime_sources"], [row]))
        self.assertIsNotNone(checks.known_failure("nullable_counts", [4, 4], row["runtime_sources"], [row]))
        self.assertIsNone(checks.known_failure("nullable_counts", [4, 5], row["runtime_sources"], [row]))

    def test_object_absence_requires_structured_missing_key_not_auth_or_network_failure(self):
        self.assertEqual(checks.object_state('{"status":"success"}', 0), "present")
        self.assertEqual(checks.object_state('{"status":"error","error":{"cause":{"Code":"NoSuchKey"}}}', 1), "gone")
        for code in ("AccessDenied", "NoSuchBucket", "ConnectionError"):
            with self.subTest(code=code), self.assertRaises(ValueError):
                checks.object_state(json.dumps({"status": "error", "error": {"Code": code}}), 1)

    def test_code_free_pinned_mc_missing_object_needs_an_independent_empty_listing(self):
        text = json.dumps({"status": "error", "error": {"type": "fatal", "cause": {"message": "Object does not exist", "error": {}}}})
        with self.assertRaises(ValueError):
            checks.object_state(text, 1)
        self.assertEqual(checks.object_state(text, 1, "", 0), "gone")
        for listing, code in (("", 1), ('{"status":"success","key":"file"}', 0), ('{"status":"error"}', 1)):
            with self.subTest(listing=listing, code=code), self.assertRaises(ValueError):
                checks.object_state(text, 1, listing, code)
        denied = json.loads(text)
        denied["error"]["cause"]["message"] = "Access denied"
        with self.assertRaises(ValueError):
            checks.object_state(json.dumps(denied), 1, "", 0)


if __name__ == "__main__":
    unittest.main()
