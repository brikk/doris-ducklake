#!/usr/bin/env bash
# Internal continuation: the supervisor holds the daemon claim through every
# assertion and verifies the complete ledger before publishing overall success.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JVM_ROOT="$(cd "$HERE/../.." && pwd)"
TRINO_COMPOSE="$JVM_ROOT/trino-ducklake/compose/docker-compose.yml"
UP_ONLY="$1"
deployment_state="$2"
FE_CONTAINER=$(python3 -c 'import json,sys; p=json.load(open(sys.argv[1])); assert p["deployment_attested"] and p["loaded_provider_verified"]; print(p["new_process"]["id"])' "$deployment_state")
fe_exec() { docker exec "$FE_CONTAINER" "$@"; }
log() { printf '\033[1;36m[smoke]\033[0m %s\n' "$*"; }
fe_sql() { fe_exec mysql -h127.0.0.1 -P9030 -uroot -B -r -N -e "$1"; }
be_image=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["runtime_images"]["be"]["reference"])' "$deployment_state")
if [[ "$be_image" == "apache/doris:be-4.1.4" ]]; then
    fe_sql "SET GLOBAL enable_local_shuffle_planner = false;"
else
    fe_sql "SET GLOBAL enable_local_shuffle_planner = true;"
fi
if [[ "$UP_ONLY" -eq 1 ]]; then
    log "--up-only: deployment attested; smoke correctness was not exercised."
    log "FE container: $FE_CONTAINER (SQL 9030 inside the container; see compose for host mappings)"
    exit 0
fi

# The parent resolves these from the selected compose configuration, verifies
# container/image/network ownership and overwrites inherited target/secret vars.
: "${SMOKE_PG_CONTAINER:?}" "${SMOKE_MINIO_CONTAINER:?}" "${SMOKE_SUBSTRATE_NETWORK:?}"
: "${SMOKE_PG_USER:?}" "${SMOKE_PG_PASSWORD:?}" "${SMOKE_S3_KEY_ID:?}" "${SMOKE_S3_SECRET:?}"
: "${SMOKE_PYTHON_IMAGE:?}" "${SMOKE_MC_IMAGE:?}"
CURRENT_CHECK=allocation
checks() { python3 "$HERE/smoke_checks.py" "$@"; }
on_exit() {
    local status=$?
    trap - EXIT
    if [[ $status -ne 0 ]]; then
        checks abort --state "$deployment_state" --case "$CURRENT_CHECK" --exit-code "$status" || true
        log "Smoke FAILED at $CURRENT_CHECK; run-owned fixtures and assertion evidence retained."
    fi
    exit "$status"
}
trap on_exit EXIT
check_numbers() { local case_id=$1 operation=$2 expected=$3; shift 3; checks "$operation" --state "$deployment_state" --case "$case_id" --expected "$expected" --actual "$@"; }
check_json() { checks json --state "$deployment_state" --case "$1" --expected "$2" --actual "$3"; }
psql_c() { docker exec "$SMOKE_PG_CONTAINER" psql -X -v ON_ERROR_STOP=1 -U "$SMOKE_PG_USER" -d "$SMOKE_PG_DB" -tA -c "$1"; }
mc_run() {
    docker run --rm --pull=never --network "$SMOKE_SUBSTRATE_NETWORK" \
        -e "S3_HOST=$SMOKE_MINIO_CONTAINER" -e "S3_KEY_ID=$SMOKE_S3_KEY_ID" -e "S3_SECRET=$SMOKE_S3_SECRET" \
        --entrypoint sh "$SMOKE_MC_IMAGE" -ec '
          mc alias set m "http://$S3_HOST:9000" "$S3_KEY_ID" "$S3_SECRET" >/dev/null
          sh -ec "$1"
        ' sh "$1"
}
native_helper() {
    local script=$1; shift
    docker run --rm --pull=never --network "$SMOKE_SUBSTRATE_NETWORK" \
        -v "$HERE/$script:/script.py:ro" -v "$HERE/smoke_lake.py:/smoke_lake.py:ro" -v "$HERE/smoke_rows.py:/smoke_rows.py:ro" \
        -e "SMOKE_RUN_ID=$SMOKE_RUN_ID" -e "PG_HOST=$SMOKE_PG_CONTAINER" -e "PG_DB=$SMOKE_PG_DB" \
        -e "PG_USER=$SMOKE_PG_USER" -e "PG_PASSWORD=$SMOKE_PG_PASSWORD" \
        -e "S3_ENDPOINT=$SMOKE_MINIO_CONTAINER:9000" -e "S3_KEY_ID=$SMOKE_S3_KEY_ID" -e "S3_SECRET=$SMOKE_S3_SECRET" \
        -e "DATA_PATH=$SMOKE_DATA_PATH" "$@" "$SMOKE_PYTHON_IMAGE" sh -ec '
          pip install --quiet --no-cache-dir "duckdb==1.5.6" >&2
          exec python /script.py
        '
}
oracle_rows() { native_helper smoke_rows.py -e "ORACLE_QUERY=$1"; }
check_rows() { fe_sql "$3" | checks rows --state "$deployment_state" --case "$1" --expected "$2"; }
write_rows() {
    local case_id=$1 schema=$2 table=$3 fixture=$4 expected
    native_helper w2-insert.py -e MODE=verify -e "SCHEMA=$schema" -e "TABLE=$table" -e PARTITION_BY= -e "EXPECTED_FIXTURE=$fixture"
    expected=$(checks fixture --expected "$fixture")
    check_rows "$case_id" "$expected" "SELECT JSON_ARRAY(id,name) FROM ${SMOKE_CATALOG}.${schema}.${table} ORDER BY id;"
}
object_state() {
    local body status listing="" listing_status=1
    if body=$(mc_run "mc stat --json ${SMOKE_MC_ROOT}$1"); then status=0; else status=$?; fi
    if [[ $status -ne 0 ]]; then
        if listing=$(mc_run "mc ls --json ${SMOKE_MC_ROOT}$1"); then listing_status=0; else listing_status=$?; fi
    fi
    printf '%s' "$body" | checks object-state --exit-code "$status" --listing-exit-code "$listing_status" --listing-json "$listing"
}
procedure_counts() {
    local row
    row=$(fe_sql "$2")
    checks procedure --expected "$1" --actual "$row"
}

# Namespace is internal and CREATE must acknowledge it; no adopt-existing or
# prefix-wide cleanup. Keep all fixtures on failure, including failed writes.
SMOKE_RUN_ID=$(docker exec "$SMOKE_PG_CONTAINER" psql -X -v ON_ERROR_STOP=1 -U "$SMOKE_PG_USER" -d postgres -tA -c "SELECT replace(gen_random_uuid()::text, '-', '');")
if [[ ! "$SMOKE_RUN_ID" =~ ^[0-9a-f]{32}$ ]]; then log "Invalid smoke run ID; refusing to allocate a lake."; exit 1; fi
readonly SMOKE_RUN_ID
readonly SMOKE_PG_DB="doris_smoke_${SMOKE_RUN_ID}" SMOKE_CATALOG="doris_smoke_${SMOKE_RUN_ID}"
readonly SMOKE_PREFIX="doris-smoke/${SMOKE_RUN_ID}/" SMOKE_DATA_PATH="s3://ducklake/doris-smoke/${SMOKE_RUN_ID}/"
readonly SMOKE_MC_ROOT="m/ducklake/doris-smoke/${SMOKE_RUN_ID}/"
export SMOKE_RUN_ID PG_DB="$SMOKE_PG_DB" DATA_PATH="$SMOKE_DATA_PATH"
checks init --state "$deployment_state" --run-id "$SMOKE_RUN_ID"
log "Run $SMOKE_RUN_ID: catalog=$SMOKE_CATALOG database=$SMOKE_PG_DB warehouse=$SMOKE_DATA_PATH"
mc_run "mc mb --ignore-existing m/ducklake"
prefix_contents=$(mc_run "mc ls '$SMOKE_MC_ROOT'")
if [[ -n "$prefix_contents" ]]; then log "Smoke prefix already exists; refusing to overwrite any objects."; exit 1; fi
docker exec "$SMOKE_PG_CONTAINER" psql -X -v ON_ERROR_STOP=1 -U "$SMOKE_PG_USER" -d postgres -c "CREATE DATABASE $SMOKE_PG_DB TEMPLATE template0;"
DUCKDB_VERSION=1.5.6 docker compose -f "$TRINO_COMPOSE" run --rm --no-deps \
    -e "PG_HOST=$SMOKE_PG_CONTAINER" -e "PG_DB=$SMOKE_PG_DB" -e "PG_USER=$SMOKE_PG_USER" -e "PG_PASSWORD=$SMOKE_PG_PASSWORD" \
    -e "S3_ENDPOINT=$SMOKE_MINIO_CONTAINER:9000" -e "S3_KEY_ID=$SMOKE_S3_KEY_ID" -e "S3_SECRET=$SMOKE_S3_SECRET" \
    -e S3_BUCKET=ducklake -e "S3_DATA_PREFIX=$SMOKE_PREFIX" -e TPCH_SCHEMA=tpch -e TPCH_SCALE_FACTOR=0.01 bootstrap
persisted_path=$(psql_c "SELECT value FROM ducklake_metadata WHERE key = 'data_path';")
if [[ "$persisted_path" != "$SMOKE_DATA_PATH" ]]; then log "Seeded catalog data_path does not match the owned warehouse."; exit 1; fi

CURRENT_CHECK=tpch_counts
fe_sql "$(checks catalog-sql --expected create)"
orders=$(fe_sql "SELECT COUNT(*) FROM ${SMOKE_CATALOG}.tpch.orders;")
lineitem=$(fe_sql "SELECT COUNT(*) FROM ${SMOKE_CATALOG}.tpch.lineitem;")
check_numbers tpch_counts eq 15000,60175 "$orders" "$lineitem"
CURRENT_CHECK=tpch_sample
reference=$(oracle_rows tpch_sample)
check_rows tpch_sample "$reference" "SELECT JSON_ARRAY(o_orderkey,o_orderstatus) FROM ${SMOKE_CATALOG}.tpch.orders ORDER BY o_orderkey LIMIT 5;"

CURRENT_CHECK=nullable_counts
fe_sql "CREATE TABLE ${SMOKE_CATALOG}.tpch.count_col_check (id INT,v INT); INSERT INTO ${SMOKE_CATALOG}.tpch.count_col_check VALUES (1,10),(2,NULL),(3,30),(4,NULL);"
star=$(fe_sql "SELECT COUNT(*) FROM ${SMOKE_CATALOG}.tpch.count_col_check;")
col=$(fe_sql "SELECT COUNT(v) FROM ${SMOKE_CATALOG}.tpch.count_col_check;")
check_numbers nullable_counts eq 4,2 "$star" "$col"
CURRENT_CHECK=nullable_rows
reference=$(oracle_rows nullable_rows)
check_rows nullable_rows "$reference" "SELECT JSON_ARRAY(id,v) FROM ${SMOKE_CATALOG}.tpch.count_col_check ORDER BY id;"
fe_sql "DROP TABLE ${SMOKE_CATALOG}.tpch.count_col_check;"
CURRENT_CHECK=filter_plan
fe_sql "EXPLAIN VERBOSE SELECT o_orderkey,o_orderstatus FROM ${SMOKE_CATALOG}.tpch.orders WHERE o_orderkey < 100;" |
    checks plan --state "$deployment_state" --case filter_plan
CURRENT_CHECK=filter_rows
reference=$(oracle_rows filter_rows)
check_rows filter_rows "$reference" "SELECT JSON_ARRAY(o_orderkey,o_orderstatus) FROM ${SMOKE_CATALOG}.tpch.orders WHERE o_orderkey < 100 ORDER BY o_orderkey;"

CURRENT_CHECK=delete_file
native_helper step7-delete.py -e DELETE_COUNT=7
active=$(psql_c "SELECT COUNT(*) FROM ducklake_delete_file WHERE end_snapshot IS NULL;")
check_numbers delete_file ge 1 "$active"
# Snapshot/catalog refresh is NOT a provider reload; F24's fresh-process gate
# already proved the factory identity before allocation.
fe_sql "$(checks catalog-sql --expected recreate)"
CURRENT_CHECK=delete_count
remaining=$(fe_sql "SELECT COUNT(*) FROM ${SMOKE_CATALOG}.tpch.step7_orders;")
if [[ "$remaining" != 93 ]]; then log "Step 7 FAIL: Doris returned $remaining rows, expected 93."; fi
check_numbers delete_count eq 93 "$remaining"
CURRENT_CHECK=delete_rows
reference=$(oracle_rows delete_rows)
check_rows delete_rows "$reference" "SELECT JSON_ARRAY(o_orderkey,o_orderstatus) FROM ${SMOKE_CATALOG}.tpch.step7_orders ORDER BY o_orderkey;"

CURRENT_CHECK=ddl_schema_create
fe_sql "CREATE DATABASE ${SMOKE_CATALOG}.ddl_smoke;"
schemas=$(psql_c "SELECT COUNT(*) FROM ducklake_schema WHERE schema_name='ddl_smoke' AND end_snapshot IS NULL;")
check_numbers ddl_schema_create eq 1 "$schemas"
CURRENT_CHECK=ddl_columns
fe_sql "CREATE TABLE ${SMOKE_CATALOG}.ddl_smoke.doris_ddl (id INT,name STRING);"
columns=$(psql_c "SELECT COALESCE(json_agg(c.column_name ORDER BY c.column_order)::text,'[]') FROM ducklake_column c JOIN ducklake_table t ON t.table_id=c.table_id JOIN ducklake_schema s ON s.schema_id=t.schema_id WHERE s.schema_name='ddl_smoke' AND t.table_name='doris_ddl' AND c.end_snapshot IS NULL AND c.parent_column IS NULL;")
check_json ddl_columns '["id","name"]' "$columns"
CURRENT_CHECK=ddl_empty
write_rows ddl_empty ddl_smoke doris_ddl empty
CURRENT_CHECK=ddl_insert
fe_sql "INSERT INTO ${SMOKE_CATALOG}.ddl_smoke.doris_ddl VALUES (1,'alice'),(2,'bob');"
write_rows ddl_insert ddl_smoke doris_ddl two
CURRENT_CHECK=ddl_bucket
fe_sql "CREATE TABLE ${SMOKE_CATALOG}.ddl_smoke.doris_ddl_p (id INT,name STRING) PARTITION BY LIST (bucket(4,name)) ();"
transforms=$(psql_c "SELECT COALESCE(json_agg(lower(replace(pc.transform,' ','')) ORDER BY pc.partition_key_index)::text,'[]') FROM ducklake_partition_column pc JOIN ducklake_partition_info pi ON pi.partition_id=pc.partition_id JOIN ducklake_table t ON t.table_id=pi.table_id JOIN ducklake_schema s ON s.schema_id=t.schema_id WHERE s.schema_name='ddl_smoke' AND t.table_name='doris_ddl_p' AND pi.end_snapshot IS NULL;")
check_json ddl_bucket '["bucket(4)"]' "$transforms"
CURRENT_CHECK=ddl_drop
fe_sql "DROP TABLE ${SMOKE_CATALOG}.ddl_smoke.doris_ddl; DROP TABLE ${SMOKE_CATALOG}.ddl_smoke.doris_ddl_p; DROP DATABASE ${SMOKE_CATALOG}.ddl_smoke;"
schemas=$(psql_c "SELECT COUNT(*) FROM ducklake_schema WHERE schema_name='ddl_smoke' AND end_snapshot IS NULL;")
check_numbers ddl_drop eq 0 "$schemas"
log "W1 DDL: required create/schema/empty/insert/partition/drop assertions passed."

CURRENT_CHECK=w2_rows
fe_sql "CREATE TABLE ${SMOKE_CATALOG}.tpch.doris_w (id INT,name STRING); INSERT INTO ${SMOKE_CATALOG}.tpch.doris_w VALUES (1,'alice'),(2,'bob'),(3,'charlie');"
write_rows w2_rows tpch doris_w three
CURRENT_CHECK=w2_files
files=$(psql_c "SELECT COUNT(*) FROM ducklake_data_file f JOIN ducklake_table t ON t.table_id=f.table_id WHERE t.table_name='doris_w' AND f.end_snapshot IS NULL;")
check_numbers w2_files ge 1 "$files"
CURRENT_CHECK=w2c_rows
fe_sql "CREATE TABLE ${SMOKE_CATALOG}.tpch.doris_wb (id INT,name STRING) PARTITION BY LIST (bucket(4,name)) (); INSERT INTO ${SMOKE_CATALOG}.tpch.doris_wb VALUES (1,'alice'),(2,'bob'),(3,'charlie');"
write_rows w2c_rows tpch doris_wb three
CURRENT_CHECK=w2c_buckets
buckets=$(psql_c "SELECT pv.partition_value FROM ducklake_file_partition_value pv JOIN ducklake_table t ON t.table_id=pv.table_id JOIN ducklake_data_file f ON f.data_file_id=pv.data_file_id WHERE t.table_name='doris_wb' AND f.end_snapshot IS NULL ORDER BY pv.partition_value;")
bucket_array=()
while IFS= read -r value; do bucket_array+=("$value"); done <<< "$buckets"
check_numbers w2c_buckets eq 1,2,3 "${bucket_array[@]}"
CURRENT_CHECK=w2c_pruning
alice=$(fe_sql "SELECT COUNT(*) FROM ${SMOKE_CATALOG}.tpch.doris_wb WHERE name='alice';")
check_numbers w2c_pruning eq 1 "$alice"

# Keep INT32/STRING source types. Narrow-int Iceberg writer coverage is a known
# separate engine limitation, not a silently accepted failure in this workload.
CURRENT_CHECK=ctas_rows
fe_sql "CREATE TABLE ${SMOKE_CATALOG}.tpch.doris_ctas AS SELECT id,name FROM ${SMOKE_CATALOG}.tpch.doris_w;"
write_rows ctas_rows tpch doris_ctas three
CURRENT_CHECK=ctas_files
files=$(psql_c "SELECT COUNT(*) FROM ducklake_data_file f JOIN ducklake_table t ON t.table_id=f.table_id WHERE t.table_name='doris_ctas' AND f.end_snapshot IS NULL;")
check_numbers ctas_files ge 1 "$files"
fe_sql "DROP TABLE ${SMOKE_CATALOG}.tpch.doris_ctas;"

CURRENT_CHECK=defaults
native_helper step-default.py
fe_sql "REFRESH CATALOG $SMOKE_CATALOG;"
reference=$(oracle_rows defaults)
check_rows defaults "$reference" "SELECT JSON_ARRAY(a,b) FROM ${SMOKE_CATALOG}.tpch.default_probe ORDER BY a;"
fe_sql "DROP TABLE ${SMOKE_CATALOG}.tpch.default_probe;"

GC_ANCHOR="${SMOKE_CATALOG}.tpch.orders"
CURRENT_CHECK=expiry
before=$(psql_c "SELECT COUNT(*) FROM ducklake_snapshot;")
sleep 2
counts=$(procedure_counts expiry "ALTER TABLE $GC_ANCHOR EXECUTE expire_snapshots('retention_threshold'='1s');")
IFS=$'\t' read -r expired ignored <<< "$counts"
after=$(psql_c "SELECT COUNT(*) FROM ducklake_snapshot;")
checks stats --state "$deployment_state" --case expiry --actual "$expired" "$before" "$after"
CURRENT_CHECK=cleanup
mc_run "printf 'cleanup-fixture' | mc pipe ${SMOKE_MC_ROOT}ducklake-smoke-cleanup.parquet"
psql_c "INSERT INTO ducklake_files_scheduled_for_deletion (data_file_id,path,path_is_relative,schedule_start) VALUES (900001,'${SMOKE_DATA_PATH}ducklake-smoke-cleanup.parquet',false,now()-interval '1 hour');" >/dev/null
counts=$(procedure_counts cleanup "ALTER TABLE $GC_ANCHOR EXECUTE cleanup_old_files('retention_threshold'='1s');")
IFS=$'\t' read -r deleted failed <<< "$counts"
blob=$(object_state ducklake-smoke-cleanup.parquet)
scheduled=$(psql_c "SELECT COUNT(*) FROM ducklake_files_scheduled_for_deletion WHERE data_file_id=900001;")
checks stats --state "$deployment_state" --case cleanup --actual "$deleted" "$failed" "$blob" "$scheduled"
CURRENT_CHECK=orphans
mc_run "printf 'orphan' | mc pipe ${SMOKE_MC_ROOT}ducklake-smoke-orphan.parquet; printf 'success' | mc pipe ${SMOKE_MC_ROOT}_SUCCESS; printf 'foreign' | mc pipe ${SMOKE_MC_ROOT}data.parquet"
sleep 2
counts=$(procedure_counts orphans "ALTER TABLE $GC_ANCHOR EXECUTE remove_orphan_files('scope'='catalog','retention_threshold'='1s');")
IFS=$'\t' read -r deleted failed <<< "$counts"
orphan=$(object_state ducklake-smoke-orphan.parquet)
success=$(object_state _SUCCESS)
foreign=$(object_state data.parquet)
checks stats --state "$deployment_state" --case orphans --actual "$deleted" "$failed" "$orphan" "$success" "$foreign"
CURRENT_CHECK=gc_referenced
# A metadata COUNT(*) can pass even after a referenced Parquet was lost. Read
# exact fields in BOTH engines, forcing actual referenced-file consumption.
write_rows gc_referenced tpch doris_w three
CURRENT_CHECK=fixture_cleanup
# The orphan is already proven absent. This mc version rejects removal of an
# absent key even with --force; remove only the two markers proven present.
mc_run "mc rm --force ${SMOKE_MC_ROOT}_SUCCESS ${SMOKE_MC_ROOT}data.parquet"
for file in _SUCCESS data.parquet ducklake-smoke-orphan.parquet; do
    if [[ "$(object_state "$file")" != gone ]]; then log "Owned test marker survived cleanup"; exit 1; fi
done
check_json fixture_cleanup true true
checks finish --state "$deployment_state"
log "All required supported-scope assertions passed; supervisor finalization pending."
