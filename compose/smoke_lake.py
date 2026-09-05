"""Reject smoke helpers targeting anything but the current run's isolated lake."""

import os
import re


def require_smoke_lake() -> None:
    token = os.environ.get("SMOKE_RUN_ID", "")
    if re.fullmatch(r"[0-9a-f]{32}", token) is None:
        raise ValueError("SMOKE_RUN_ID must be exactly 32 lowercase hex characters")
    if os.environ.get("PG_DB") != f"doris_smoke_{token}":
        raise ValueError("PG_DB must be doris_smoke_<SMOKE_RUN_ID>")
    if os.environ.get("DATA_PATH") != f"s3://ducklake/doris-smoke/{token}/":
        raise ValueError("DATA_PATH must be s3://ducklake/doris-smoke/<SMOKE_RUN_ID>/")
