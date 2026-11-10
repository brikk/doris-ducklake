"""The blocker regression must preserve the upstream BOOLEAN dictionary encoding."""
import base64
import hashlib
from pathlib import Path
import unittest


class BooleanDictionaryFixtureTest(unittest.TestCase):
    def test_original_upstream_fixture_bytes_are_not_rewritten(self):
        encoded = Path(__file__).with_name("fixtures") / "boolean_rle_dictionary.parquet.b64"
        payload = base64.b64decode(encoded.read_text().strip(), validate=True)
        self.assertEqual(len(payload), 257)
        self.assertEqual(hashlib.sha256(payload).hexdigest(),
                         "ba2016dcf7ba8af572371491f0b6701afb0b79c06a85930a80598628a020e5b2")
        self.assertEqual(payload[:4], b"PAR1")
        self.assertEqual(payload[-4:], b"PAR1")


if __name__ == "__main__":
    unittest.main()
