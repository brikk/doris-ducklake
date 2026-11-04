"""Cluster-free guards for the isolated full-corpus controller."""
import unittest

from corpus_acceptance import gradle_init, replay_measurements, selected_hashes


def suite(summary, skipped=0, failures=0):
    return (f'<testsuite tests="1" failures="{failures}" errors="0" skipped="{skipped}">'
            '<system-out><![CDATA[' + summary + ']]></system-out></testsuite>')


SUMMARY = ("corpus replay: 475 files\n  ran 400 (records: 100 passed, 2 failed, 7 skipped)\n"
           "  mirrors: 12 attempted, 10 passed, 2 failed, 40 skipped\n")


class CorpusAcceptanceTest(unittest.TestCase):
    def test_explicit_selection_cannot_claim_full_discovery_or_escape_it(self):
        hashes = {"a.test": "a", "b.test": "b"}
        self.assertEqual(selected_hashes(hashes, ["b.test"]), {"b.test": "b"})
        self.assertEqual(hashes, {"a.test": "a", "b.test": "b"})
        for names in ([], ["a.test", "a.test"], ["../a.test"], ["missing.test"]):
            with self.subTest(names=names), self.assertRaises(ValueError):
                selected_hashes(hashes, names)

    def test_init_scopes_both_hooks_to_consumer_not_included_build(self):
        text = gradle_init("/project", "/project/build/private", {"ducklake.corpus.dirs": "all"})
        self.assertIn('p.projectDir == new File("/project")', text)
        self.assertIn('if (rootProject.projectDir == new File("/project"))', text)
        self.assertIn('tasks.named("corpusReplayTest")', text)
        self.assertIn('systemProperty "ducklake.corpus.dirs", "all"', text)

    def test_explicit_live_task_keeps_included_build_guard(self):
        text = gradle_init("/project", "/project/build/live", {}, task="readSafetyLiveTest")
        self.assertIn('if (rootProject.projectDir == new File("/project"))', text)
        self.assertIn('tasks.named("readSafetyLiveTest")', text)

    def test_measured_failures_are_not_discarded(self):
        result = replay_measurements(suite(SUMMARY, failures=1), 475)
        self.assertEqual(result["records"]["failed"], 2)
        self.assertEqual(result["mirrors"], {"attempted": 12, "passed": 10, "failed": 2, "skipped": 40})
        self.assertEqual(result["junit"]["failures"], 1)
        self.assertFalse(result["timed_out_or_blocked"])

    def test_assumption_skip_cannot_count_as_execution(self):
        with self.assertRaisesRegex(ValueError, "did not execute"):
            replay_measurements(suite(SUMMARY, skipped=1), 475)

    def test_no_summary_or_incomplete_discovery_refuses(self):
        with self.assertRaisesRegex(ValueError, "Missing measured"):
            replay_measurements(suite(""), 475)
        with self.assertRaisesRegex(ValueError, "incomplete"):
            replay_measurements(suite(SUMMARY), 476)

    def test_zero_successful_mirrors_refuses(self):
        with self.assertRaisesRegex(ValueError, "No successful Doris mirrors"):
            replay_measurements(suite(SUMMARY.replace("10 passed", "0 passed")), 475)

    def test_timeouts_and_quarantine_are_visible(self):
        for reason in ("TIMEOUT: slow file", "BLOCKED: quarantined worker"):
            with self.subTest(reason=reason):
                self.assertTrue(replay_measurements(suite(SUMMARY + reason), 475)["timed_out_or_blocked"])

    def test_format_scope_halts_are_separate_from_completed_files(self):
        result = replay_measurements(suite(SUMMARY + "  halted mid-file by format scope 14 (records above include theirs)\n"), 475)
        self.assertEqual(result["format_scope_halts"], 14)
        self.assertEqual(result["records"]["ran_files"], 400)
        self.assertFalse(result["timed_out_or_blocked"])


if __name__ == "__main__":
    unittest.main()
