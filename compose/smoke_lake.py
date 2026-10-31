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


def sql_literal(value):
    """DuckDB standard SQL literal, not a libpq or shell encoder."""
    return "'" + value.replace("'", "''") + "'"


def doris_literal(value):
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def identifier(value):
    return '"' + value.replace('"', '""') + '"'


def postgres_attach_uri():
    def option(key, value):
        return key + "='" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
    return "ducklake:postgres:" + " ".join(option(k, os.environ[v]) for k, v in
        (("dbname", "PG_DB"), ("host", "PG_HOST"), ("user", "PG_USER"), ("password", "PG_PASSWORD")))


def connect_smoke(duckdb):
    require_smoke_lake()  # Always before connecting, installing or reading env secrets.
    if getattr(duckdb, "__version__", None) != "1.5.6":
        raise ValueError("Smoke oracle requires duckdb 1.5.6")
    con = duckdb.connect()
    try:
        for extension in ("ducklake", "httpfs", "postgres"):
            con.execute("INSTALL " + extension)
            con.execute("LOAD " + extension)
        con.execute("CREATE SECRET lake_s3 (TYPE S3, KEY_ID " + sql_literal(os.environ["S3_KEY_ID"]) +
                    ", SECRET " + sql_literal(os.environ["S3_SECRET"]) + ", ENDPOINT " + sql_literal(os.environ["S3_ENDPOINT"]) +
                    ", URL_STYLE 'path', USE_SSL false)")
        con.execute("ATTACH " + sql_literal(postgres_attach_uri()) + " AS lake (DATA_PATH " + sql_literal(os.environ["DATA_PATH"]) + ")")
        return con
    except BaseException:
        con.close()
        raise
