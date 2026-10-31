"""W2 / W2c (INSERT) smoke helper: a DuckDB+DuckLake side for the Doris-write E2E.

Two modes (MODE env var):
  create  → drop+recreate an empty `<SCHEMA>.<TABLE> (id INTEGER, name VARCHAR)` for Doris
            to INSERT into. If PARTITION_BY is set, the table is partitioned by that
            expression (e.g. `bucket(4, name)`). NOTE: as of W1b(b) the smoke stands its
            INSERT targets up via Doris `CREATE TABLE` (the live DDL route); this mode is
            kept only as a fallback for when the live DDL route is unavailable.
  verify  → assert exact typed rows AND INTEGER/VARCHAR schema. An explicit
             EXPECTED_FIXTURE (empty, two, three) is required; printing is not proof.

Env vars: SCHEMA (default tpch), TABLE (default doris_w), PARTITION_BY (default none);
connection details as in step7-delete.py.
"""

import os

import duckdb

from smoke_lake import require_smoke_lake, connect_smoke, identifier
from smoke_rows import FIXTURES, validate_rows, validate_write_schema


def main() -> None:
    require_smoke_lake()
    mode = os.environ.get("MODE", "create")
    schema = os.environ.get("SCHEMA", "tpch")
    table = os.environ.get("TABLE", "doris_w")
    partition_by = os.environ.get("PARTITION_BY", "").strip()

    fixture = os.environ.get("EXPECTED_FIXTURE")
    if mode not in {"create", "verify"} or (schema, table) not in {
        ("ddl_smoke", "doris_ddl"), ("tpch", "doris_w"), ("tpch", "doris_wb"), ("tpch", "doris_ctas")
    }:
        raise ValueError("Unknown write-helper mode/relation")
    if mode == "verify" and fixture not in {"empty", "two", "three"}:
        raise ValueError("Write verification needs an explicit EXPECTED_FIXTURE")
    if partition_by not in {"", "bucket(4, name)"}:
        raise ValueError("Unsupported write-helper partition fixture")
    relation = "lake." + identifier(schema) + "." + identifier(table)
    con = connect_smoke(duckdb)
    try:
        if mode == "create":
            con.execute(f"DROP TABLE IF EXISTS {relation}")
            con.execute(f"CREATE TABLE {relation} (id INTEGER, name VARCHAR)")
            if partition_by:
                con.execute(f"ALTER TABLE {relation} SET PARTITIONED BY ({partition_by})")
            print(f"Created explicit fixture {schema}.{table}; no correctness pass claimed", flush=True)
        else:
            cursor = con.execute(f"SELECT id, name FROM {relation} ORDER BY id")
            rows = cursor.fetchall()
            validate_write_schema(cursor.description)
            validate_rows(rows, FIXTURES[fixture])
            print(f"Native exact typed rows PASS: {schema}.{table}, fixture={fixture}, rows={rows}", flush=True)
    finally:
        con.close()


if __name__ == "__main__":
    main()
