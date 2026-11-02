"""Ordinary-name mixed scalar/STRUCT/LIST/MAP native fixture, independent of the N03 parser."""
import json
import os
import duckdb
from smoke_lake import connect_smoke

con = connect_smoke(duckdb)
try:
    con.execute("SET threads=1")
    con.execute("CALL lake.set_option('data_inlining_row_limit',0)")
    con.execute("CREATE TABLE lake.tpch.default_nested(id INTEGER,s STRUCT(a INTEGER,label VARCHAR),"
                "items STRUCT(x VARCHAR)[],attrs MAP(VARCHAR,STRUCT(a VARCHAR)))")
    con.execute("INSERT INTO lake.tpch.default_nested VALUES "
                "(1,{'a':7,'label':'NULL'},[{'x':'list'}],map(['k'],[{'a':'map'}])),"
                "(2,NULL,NULL,NULL),(3,{'a':NULL,'label':NULL},[NULL],map(['k'],[NULL]))")
    rows = con.execute("SELECT id,s.a,s.label,items[1].x,attrs['k'].a FROM lake.tpch.default_nested ORDER BY id").fetchall()
    expected = [[1,7,"NULL","list","map"],[2,None,None,None,None],[3,None,None,None,None]]
    assert [list(row) for row in rows] == expected
    nulls = con.execute("SELECT s IS NULL,items IS NULL,attrs IS NULL FROM lake.tpch.default_nested ORDER BY id").fetchall()
    assert nulls == [(False,False,False),(True,True,True),(False,False,False)]
    con.execute(f"ATTACH 'host={os.environ['PG_HOST']} dbname={os.environ['PG_DB']} user=ducklake "
                f"password={os.environ['PG_PASSWORD']}' AS metadata (TYPE postgres,READ_ONLY)")
    columns = con.execute("SELECT column_id,column_name,column_type,parent_column,column_order "
                          "FROM metadata.public.ducklake_column WHERE table_id=(SELECT table_id FROM metadata.public.ducklake_table "
                          "WHERE table_name='default_nested' AND end_snapshot IS NULL) ORDER BY column_id").fetchall()
    version = con.execute("SELECT value FROM metadata.public.ducklake_metadata WHERE key='version' AND scope IS NULL").fetchall()
    files = con.execute("SELECT data_file FROM ducklake_list_files('lake','default_nested',schema=>'tpch')").fetchall()
    assert files
    footers = [con.execute("SELECT name,field_id FROM parquet_schema(?)", [row[0]]).fetchall() for row in files]
    for footer in footers:
        assert {(name,field_id) for name,field_id in footer if field_id is not None} == {(name,field_id) for field_id,name,_,_,_ in columns}
    print(json.dumps({"duckdb":duckdb.__version__,"format_version":version,"expected":expected,"null_parents":nulls,
                      "columns":columns,"files":files,"parquet_field_ids":footers}))
finally:
    con.close()
