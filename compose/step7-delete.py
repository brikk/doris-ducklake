"""Step 7 smoke helper: issue a DELETE through DuckDB+DuckLake against the
trino-ducklake substrate. Reads connection details from environment variables
so the smoke script can pass them in without baking secrets into the image.

Creates (or recreates) a dedicated `tpch.step7_orders` table seeded from
`tpch.orders`, then DELETEs DELETE_COUNT rows from it. Using a dedicated
table keeps the Step 5 smoke against `tpch.orders` clean and the Step 7
exercise idempotent across runs.

DuckLake materialises the DELETE as a new position-delete parquet file
plus a ducklake_delete_file row; the next snapshot inlines that delete
file onto the affected data file (JdbcDucklakeCatalog#getDataFiles
LEFT JOIN), which is what the Doris side reads at scan time.
"""

import os

import duckdb

from smoke_lake import require_smoke_lake, connect_smoke


def main() -> None:
    require_smoke_lake()
    delete_count = int(os.environ["DELETE_COUNT"])
    if not 0 < delete_count < 100:
        raise ValueError("DELETE_COUNT must leave a nonempty 100-row fixture")
    con = connect_smoke(duckdb)
    try:
        seed_step7(con, delete_count)
    finally:
        con.close()


def seed_step7(con, delete_count):

    # DuckLake defaults DATA_INLINING_ROW_LIMIT high enough that small
    # DELETEs land in ducklake_inlined_delete_<table_id> rows in the
    # catalog DB instead of materialising as ducklake_delete_file +
    # position-delete parquet. The Doris BE iceberg reader only consumes
    # file-based deletes, so we force the file path. Idempotent — once set,
    # the row persists in ducklake_metadata.
    con.execute("CALL lake.set_option('data_inlining_row_limit', '0')")

    # Recreate the step7-only table on every run so the exercise is
    # idempotent and we never see leftover state from a previous run.
    con.execute("DROP TABLE IF EXISTS lake.tpch.step7_orders")
    con.execute(
        """
        CREATE TABLE lake.tpch.step7_orders AS
        SELECT * FROM lake.tpch.orders
        ORDER BY o_orderkey LIMIT 100
        """
    )

    before = con.execute(
        "SELECT COUNT(*) FROM lake.tpch.step7_orders"
    ).fetchone()[0]
    if type(before) is not int or before != 100:
        raise ValueError("Native delete fixture did not start with 100 rows")
    print(f"DuckDB sees {before} rows in lake.tpch.step7_orders before DELETE",
          flush=True)

    con.execute(
        f"""
        DELETE FROM lake.tpch.step7_orders
        WHERE o_orderkey IN (
            SELECT o_orderkey FROM lake.tpch.step7_orders
            ORDER BY o_orderkey LIMIT {delete_count}
        )
        """
    )

    after = con.execute(
        "SELECT COUNT(*) FROM lake.tpch.step7_orders"
    ).fetchone()[0]
    if type(after) is not int or after != 100 - delete_count:
        raise ValueError("Native delete fixture returned the wrong remaining count")
    print(
        f"DuckDB sees {after} rows in lake.tpch.step7_orders after DELETE "
        f"(deleted {before - after}, expected {delete_count})",
        flush=True,
    )

if __name__ == "__main__":
    main()
