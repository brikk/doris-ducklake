# Type mappings

How `DuckLakeTypeMapping` presents DuckLake columns to Doris, and what `DuckLakeCreateTableMapper`
accepts on `CREATE TABLE`. A mapping existing does not mean every value round-trips;
the boundary column says where it stops.

| DuckLake | Doris | Boundary |
|---|---|---|
| boolean | BOOLEAN | |
| int8 / int16 | TINYINT / SMALLINT | Reads fine. BE writes of narrow ints are unverified; use INT/BIGINT for tables Doris writes. |
| int32 / int64 | INT / BIGINT | Core read/write path. |
| uint8 / uint16 / uint32 | SMALLINT / INT / BIGINT | Read widening only; a Doris write is not domain-checked against the unsigned range (F18). Bucket pruning on these columns must use the source type's hash encoding (F05). |
| uint64 | DECIMALV3(20,0) | Read widening. |
| int128 | DECIMALV3(38,0) | Does not cover the full signed 128-bit range (N16). Not a supported INSERT target. |
| uint128 | STRING | No numeric semantics. |
| float32 / float64 | FLOAT / DOUBLE | NaN counts and unknown bounds in write statistics are open (F15/F16). |
| decimal(p,s) | DECIMALV3(p,s) | Up to precision 38, including inside nested types. |
| varchar | STRING | Pruning assumes codepoint order (F06). |
| blob | VARBINARY | |
| uuid | VARBINARY(16) | Raw bytes, not a Doris UUID type. |
| date | DATEV2 | |
| timestamp / timestamp_s / timestamp_ms | DATETIMEV2(6) / (0) / (3) | |
| timestamp_ns | DATETIMEV2(6) | Lossy. File-statistics pruning is disabled on these columns; the BE still filters (F09). |
| timestamptz | DATETIMEV2(6) | UTC instants, zone-naive type. `enable.mapping.timestamp_tz=true` maps to TIMESTAMPTZ, which needs a master BE. |
| time / timetz | STRING | |
| list / struct / map | ARRAY / STRUCT / MAP | Reads at the latest snapshot of natively written files. Refused for time travel and for tables with `add_files` name mappings. Not writable. |
| json / variant / interval | STRING | Text only; no type-specific functions. |
| geometry family | VARBINARY | Raw bytes. |

## Writes

The write mapper accepts a narrower set than the read mapper: boolean, the signed
integers, float/double, decimal, date, the timestamp variants, varchar, and blob. Nested
types, HLL/BITMAP/AGG_STATE, JSON/VARIANT and IPV4/IPV6 are rejected at `CREATE TABLE`
rather than mapped approximately. Writing into an externally created table with
unsigned, int128 or narrow-int columns is not domain-checked (F18, O05).

## Defaults

A column added with `DEFAULT` after data exists has that default back-filled on read for
older files. Only scalar defaults whose DuckDB rendering is a safe Doris literal are
surfaced; complex and binary defaults degrade to NULL on old rows. Non-finite float
defaults are not handled (M03/M04).

## Temporal domain

Doris DATE/DATETIME cover years 1..9999 and finite values only. Column statistics,
defaults and inlined values outside that range cause the table to be refused at schema
binding, before FE constant folding could turn a comparison into a silent empty result.
Missing statistics are not evidence either way.
