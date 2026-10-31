"""Exact write-helper comparisons must reject same-count wrong values/types."""

import importlib.util
import os
from pathlib import Path
import sys
from types import ModuleType
import unittest
from unittest.mock import patch


TOKEN = "0123456789abcdef0123456789abcdef"


class FakeConnection:
    description = [("id", "INTEGER"), ("name", "VARCHAR")]

    def __init__(self, rows):
        self.rows = rows

    def execute(self, *_args):
        return self

    def fetchall(self):
        return self.rows

    def close(self):
        pass


class WriteHelperRowsTests(unittest.TestCase):
    def test_same_count_wrong_values_and_wrong_native_types_fail(self):
        env = {"SMOKE_RUN_ID": TOKEN, "PG_DB": f"doris_smoke_{TOKEN}", "DATA_PATH": f"s3://ducklake/doris-smoke/{TOKEN}/",
               "PG_HOST": "owned-pg", "PG_USER": "unused", "PG_PASSWORD": "unused", "S3_ENDPOINT": "owned-minio:9000",
               "S3_KEY_ID": "unused", "S3_SECRET": "unused", "MODE": "verify", "TABLE": "doris_w", "SCHEMA": "tpch",
               "EXPECTED_FIXTURE": "three"}
        for rows in ([(1, "alice"), (2, "CORRUPTED"), (3, "charlie")],
                     [(True, "alice"), (2, "bob"), (3, "charlie")],
                     [(1.0, "alice"), (2, "bob"), (3, "charlie")]):
            with self.subTest(rows=rows):
                module = ModuleType("duckdb")
                module.__version__ = "1.5.6"
                module.connect = lambda: FakeConnection(rows)
                spec = importlib.util.spec_from_file_location("write_helper_under_test", Path(__file__).with_name("w2-insert.py"))
                helper = importlib.util.module_from_spec(spec)
                with patch.dict(sys.modules, {"duckdb": module}), patch.dict(os.environ, env, clear=True):
                    spec.loader.exec_module(helper)
                    with self.assertRaises((ValueError, RuntimeError, AssertionError)):
                        helper.main()


if __name__ == "__main__":
    unittest.main()
