"""A zero process exit or partial live evidence is never a read-safety proof."""
import unittest

from read_safety_acceptance import LIVE_MARKERS, live_receipt


def xml(tests=3, failures=0, errors=0, skipped=0, markers=LIVE_MARKERS):
    output = "\n".join("READ_SAFETY " + marker for marker in markers)
    return (f'<testsuite tests="{tests}" failures="{failures}" errors="{errors}" skipped="{skipped}">'
            '<system-out><![CDATA[' + output + ']]></system-out></testsuite>')


class ReadSafetyReceiptTest(unittest.TestCase):
    def test_all_three_real_test_groups_and_markers_are_required(self):
        self.assertTrue(live_receipt(xml(), 0)["complete"])

    def test_partial_skipped_failed_or_nonzero_runs_cannot_pass(self):
        for text, code in ((xml(tests=0), 0), (xml(tests=2), 0), (xml(skipped=1), 0),
                           (xml(failures=1), 0), (xml(errors=1), 0), (xml(), 1)):
            with self.subTest(text=text, code=code):
                self.assertFalse(live_receipt(text, code)["complete"])

    def test_missing_any_marker_is_not_complete(self):
        for marker in LIVE_MARKERS:
            partial = tuple(value for value in LIVE_MARKERS if value != marker)
            self.assertFalse(live_receipt(xml(markers=partial), 0)["complete"])


if __name__ == "__main__":
    unittest.main()
