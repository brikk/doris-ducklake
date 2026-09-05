"""Pure isolation tests; DuckDB and external services are never used."""

import os
from pathlib import Path
import runpy
import sys
from types import ModuleType
import unittest
from unittest.mock import Mock, patch

from smoke_lake import require_smoke_lake


TOKEN = "0123456789abcdef0123456789abcdef"
OTHER_TOKEN = "f" * 32
VALID_ENV = {
    "SMOKE_RUN_ID": TOKEN,
    "PG_DB": f"doris_smoke_{TOKEN}",
    "DATA_PATH": f"s3://ducklake/doris-smoke/{TOKEN}/",
}
INVALID_VALUES = {
    "SMOKE_RUN_ID": (
        None, "", TOKEN[:-1], TOKEN + "0", TOKEN.upper(), "g" * 32,
        " " + TOKEN, TOKEN + "\n", "../" + TOKEN, "'" + TOKEN,
    ),
    "PG_DB": (
        None, "", "ducklake", "doris_smoke", f"doris_smoke_{OTHER_TOKEN}",
        VALID_ENV["PG_DB"] + " ", VALID_ENV["PG_DB"] + "'",
        VALID_ENV["PG_DB"] + "\n",
    ),
    "DATA_PATH": (
        None, "", "/", "s3://", "s3://ducklake", "s3://ducklake/",
        "s3://ducklake/data/", "s3://ducklake/doris-smoke/",
        f"s3://ducklake/doris-smoke/{OTHER_TOKEN}/",
        f"s3://other-bucket/doris-smoke/{TOKEN}/",
        f"s3://ducklake/other-prefix/{TOKEN}/",
        f"s3a://ducklake/doris-smoke/{TOKEN}/",
        f"file:///ducklake/doris-smoke/{TOKEN}/",
        f"s3://ducklake/doris-smoke/../{TOKEN}/",
        f"s3://ducklake/doris-smoke/%2e%2e/{TOKEN}/",
        VALID_ENV["DATA_PATH"].rstrip("/"),
        VALID_ENV["DATA_PATH"] + "../", VALID_ENV["DATA_PATH"] + "./",
        VALID_ENV["DATA_PATH"] + "/", VALID_ENV["DATA_PATH"] + "child/",
        VALID_ENV["DATA_PATH"] + "'", VALID_ENV["DATA_PATH"] + "\n",
    ),
}


def invalid_environments():
    for name, values in INVALID_VALUES.items():
        for value in values:
            env = VALID_ENV.copy()
            if value is None:
                del env[name]
            else:
                env[name] = value
            yield name, value, env


class SmokeLakeTests(unittest.TestCase):
    def test_valid_config(self):
        with patch.dict(os.environ, VALID_ENV, clear=True):
            self.assertIsNone(require_smoke_lake())

    def test_invalid_config(self):
        for name, value, env in invalid_environments():
            with self.subTest(name=name, value=value):
                with patch.dict(os.environ, env, clear=True):
                    with self.assertRaisesRegex(ValueError, name):
                        require_smoke_lake()

    def test_helpers_reject_before_connect(self):
        helpers = (
            ("step7-delete.py", "create"),
            ("step-default.py", "create"),
            ("w2-insert.py", "create"),
            ("w2-insert.py", "verify"),
        )
        for script, mode in helpers:
            for name, value, env in invalid_environments():
                with self.subTest(script=script, mode=mode, name=name, value=value):
                    fake_duckdb = ModuleType("duckdb")
                    connect = Mock(
                        side_effect=AssertionError("DuckDB must not connect")
                    )
                    setattr(fake_duckdb, "connect", connect)
                    env.update({
                        "PG_HOST": "unused", "PG_USER": "unused",
                        "PG_PASSWORD": "unused", "S3_ENDPOINT": "unused",
                        "S3_KEY_ID": "unused", "S3_SECRET": "unused",
                        "DELETE_COUNT": "1", "MODE": mode,
                    })
                    with patch.dict(sys.modules, {"duckdb": fake_duckdb}):
                        with patch.dict(os.environ, env, clear=True):
                            with self.assertRaisesRegex(ValueError, name):
                                runpy.run_path(
                                    str(Path(__file__).with_name(script)),
                                    run_name="__main__",
                                )
                    connect.assert_not_called()


if __name__ == "__main__":
    unittest.main()
