"""Native 1.0 file-only fixtures in one acknowledged disposable smoke lake."""
import json
import os
import duckdb
from smoke_lake import connect_smoke

con = connect_smoke(duckdb)
try:
    con.execute("SET threads=1")
    con.execute("CALL lake.set_option('data_inlining_row_limit',0)")
    con.execute('''CREATE TABLE lake.tpch.n03(id INTEGER,
        s STRUCT("a,b" INTEGER,"MiXeD" VARCHAR,"x:y" DECIMAL(18,2),"q""uote" VARCHAR,
                 "back\\slash" VARCHAR," spaced " VARCHAR,"雪" VARCHAR,"<angle>" VARCHAR),
        items STRUCT("x:y" VARCHAR)[],attrs MAP(VARCHAR,STRUCT("a,b" VARCHAR)))''')
    con.execute('''INSERT INTO lake.tpch.n03 VALUES
        (1,{'a,b':7,'MiXeD':'NULL','x:y':12.34,'q"uote':'quote','back\\slash':'slash',
            ' spaced ':'space','雪':'snow','<angle>':'angle'},[{'x:y':'list'}],map(['k'],[{'a,b':'map'}])),
        (2,NULL,NULL,NULL),
        (3,{'a,b':NULL,'MiXeD':NULL,'x:y':NULL,'q"uote':NULL,'back\\slash':NULL,
            ' spaced ':NULL,'雪':NULL,'<angle>':NULL},[NULL],map(['k'],[NULL]))''')
    con.execute("CREATE TABLE lake.tpch.replay_values(id INTEGER,b BOOLEAN,f FLOAT,d DOUBLE,payload BLOB)")
    con.execute("INSERT INTO lake.tpch.replay_values VALUES(0,true,0.1,1e16,from_hex('0022275C7FFF')),"
                "(1,false,'-0.0'::FLOAT,1e-5,from_hex('41')),(2,NULL,NULL,NULL,NULL)")
    boolean_file = os.environ["DATA_PATH"] + "boolean-dictionary.parquet"
    boolean_encodings = con.execute("SELECT path_in_schema,encodings FROM parquet_metadata(?)", [boolean_file]).fetchall()
    assert boolean_encodings == [("id", "PLAIN"), ("b", "PLAIN, RLE, RLE_DICTIONARY")]
    con.execute("CREATE TABLE lake.tpch.boolean_dictionary(id INTEGER,b BOOLEAN)")
    con.execute("CALL ducklake_add_data_files('lake','boolean_dictionary',?,schema='tpch')", [boolean_file])
    boolean_values = con.execute("SELECT id,b FROM lake.tpch.boolean_dictionary ORDER BY id").fetchall()
    assert boolean_values == list(enumerate([True, False, None, True, True, False, None, False, True, True]))
    # Full source identities, not only the formatted composite type projection.
    con.execute(f"ATTACH 'host={os.environ['PG_HOST']} dbname={os.environ['PG_DB']} user=ducklake "
                f"password={os.environ['PG_PASSWORD']}' AS metadata (TYPE postgres,READ_ONLY)")
    columns = con.execute("SELECT column_id,column_name,column_type,parent_column,column_order "
                          "FROM metadata.public.ducklake_column WHERE table_id=(SELECT table_id FROM metadata.public.ducklake_table "
                          "WHERE table_name='n03' AND end_snapshot IS NULL) ORDER BY column_id").fetchall()
    values = con.execute('''SELECT id,s."a,b",s."MiXeD",CAST(s."x:y" AS VARCHAR),s."q""uote",s."back\\slash",
        s." spaced ",s."雪",s."<angle>",items[1]."x:y",attrs['k']."a,b" FROM lake.tpch.n03 ORDER BY id''').fetchall()
    expected = [[1,7,"NULL","12.34","quote","slash","space","snow","angle","list","map"],
                [2,None,None,None,None,None,None,None,None,None,None],
                [3,None,None,None,None,None,None,None,None,None,None]]
    assert [list(row) for row in values] == expected
    files = con.execute("SELECT data_file FROM ducklake_list_files('lake','n03',schema=>'tpch')").fetchall()
    assert files
    footers = [con.execute("SELECT name,field_id FROM parquet_schema(?)", [row[0]]).fetchall() for row in files]
    identities = {(name, field_id) for field_id, name, _, _, _ in columns}
    for footer in footers:
        assert {(name, field_id) for name, field_id in footer if field_id is not None} == identities
    print(json.dumps({"duckdb": duckdb.__version__, "columns": columns, "expected": expected, "files": files,
                      "parquet_field_ids": footers, "boolean_dictionary": {
                          "file": boolean_file, "encodings": boolean_encodings, "rows": boolean_values}}))
finally:
    con.close()
