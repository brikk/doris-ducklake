"""Pinned native DuckDB reference rows and strict, typed write-fixture checks."""

import json
import os

from smoke_lake import connect_smoke


FIXTURES = {
    "empty": [], "two": [[1, "alice"], [2, "bob"]], "three": [[1, "alice"], [2, "bob"], [3, "charlie"]],
    "nullable": [[1, 10], [2, None], [3, 30], [4, None]], "defaults": [[1, 42], [2, 42], [3, 42], [4, 99]],
}
QUERIES = {
    "tpch_sample": ("SELECT o_orderkey,o_orderstatus FROM lake.tpch.orders ORDER BY o_orderkey LIMIT 5", 5, (int, str)),
    "delete_rows": ("SELECT o_orderkey,o_orderstatus FROM lake.tpch.step7_orders ORDER BY o_orderkey", 93, (int, str)),
    "filter_rows": ("SELECT o_orderkey,o_orderstatus FROM lake.tpch.orders WHERE o_orderkey < 100 ORDER BY o_orderkey", None, (int, str)),
    "nullable_rows": ("SELECT id,v FROM lake.tpch.count_col_check ORDER BY id", 4, (int, int)),
    "defaults": ("SELECT a,b FROM lake.tpch.default_probe ORDER BY a", 4, (int, int)),
}


def validate_rows(rows, expected):
    actual = [list(row) for row in rows]
    if len(actual) != len(expected):
        raise ValueError("Native row count differs from expected fixture")
    for row, wanted in zip(actual, expected):
        if len(row) != len(wanted) or any(type(a) is not type(e) or a != e for a, e in zip(row, wanted)):
            raise ValueError("Native typed rows differ from expected fixture")
    return actual


def validate_write_schema(description):
    if len(description) != 2 or [d[0] for d in description] != ["id", "name"] or [str(d[1]) for d in description] != ["INTEGER", "VARCHAR"]:
        raise ValueError("Native write fixture schema differs from INTEGER/VARCHAR contract")


def main():
    import duckdb
    key = os.environ["ORACLE_QUERY"]
    if key not in QUERIES:
        raise ValueError("Unknown native reference query")
    con = connect_smoke(duckdb)
    try:
        sql, count, types = QUERIES[key]
        rows = [list(row) for row in con.execute(sql).fetchall()]
        if (not 0 < len(rows) < 100 if count is None else len(rows) != count) or any(len(row) != 2 or any(v is not None and type(v) is not t for v, t in zip(row, types)) for row in rows):
            raise ValueError("Native reference row shape/type/count mismatch")
        if key == "nullable_rows":
            validate_rows(rows, FIXTURES["nullable"])
        if key == "defaults":
            validate_rows(rows, FIXTURES["defaults"])
        print(json.dumps({"query": key, "duckdb_version": duckdb.__version__, "rows": rows}, allow_nan=False), flush=True)
    finally:
        con.close()


if __name__ == "__main__":
    main()
