# Type mappings and limits

These are connector descriptors, **not blanket value-level/read/write qualification**.
The default lane is catalog 0.14.0 with the shared structured type parser. The
[experimental reader candidate](CATALOG-CANDIDATE.md) remains separate. Refer to the
[findings register](REVIEW-FINDINGS.md) for pruning/domain/default caveats.

| DuckLake type | Doris mapping | Important boundary |
|---|---|---|
| boolean | BOOLEAN | Scalar mapping. |
| int8 / int16 | TINYINT / SMALLINT | Narrow-int BE writes are not qualified; use INT/BIGINT. |
| int32 / int64 | INT / BIGINT | Core live write/read workload. |
| uint8 / uint16 / uint32 | SMALLINT / INT / BIGINT | Read widening is not unsigned write-domain enforcement (F18); bucket semantics need F05. |
| uint64 | DECIMALV3(20,0) | Widened read mapping. |
| int128 | DECIMALV3(38,0) | Does not hold the full signed 128-bit domain; N16 remains. Not a general supported INSERT target. |
| uint128 | STRING | Numeric semantics unavailable. |
| float32 / float64 | FLOAT / DOUBLE | NaN/unknown write statistics and non-finite defaults remain distinct gaps. |
| decimal(p,s) | DECIMALV3(p,s) | Up to precision 38, including nested signatures. |
| varchar | STRING | Binary/codepoint pruning assumptions need F06. |
| blob / uuid | VARBINARY / VARBINARY(16) | UUID is raw bytes, not a Doris UUID semantic type. |
| date | DATEV2 | Scalar mapping. |
| timestamp / timestamp_s / timestamp_ms | DATETIMEV2(6) / (0) / (3) | Microsecond/second/millisecond mapping. |
| timestamp_ns | DATETIMEV2(6) | Lossy microseconds; conservative pruning needed (F09). No native TIMESTAMP_NS adoption. |
| timestamptz | DATETIMEV2(6) by default | UTC-valued, zone-naive. `enable.mapping.timestamp_tz=true` opts into TIMESTAMPTZ; inline timezone behavior remains M10. |
| time / timetz | STRING | Connector does not adopt a first-class TIME mapping. |
| list / struct / map | ARRAY / STRUCT / MAP | Current native bounded reads; mapped/history/final-1.1 nested scans refuse. Not admitted for writes. |
| json / variant / interval | STRING | Degraded descriptor, not type-specific function/round-trip certification. |
| geometry family | VARBINARY | Raw bytes; no spatial-function semantics. |

The write mapper admits a narrower scalar set than the read mapper. CREATE and
writing to an externally created table are different paths: unsigned/lossy
targets, int128, reserved names and narrow integers need their own safeguards.
Never infer write support from the inverse type mapping or a successful DESC.

Nested native descriptors retain all parent/child IDs and ordinals. Both lanes use
the shared catalog parser, preserving quoted names, external spelling and nested
DECIMAL signatures while refusing empty/case-colliding names. Neither lane certifies nested
evolution/default backfill, imported-file identity, inline transport or history.

Missing-column scalar initial-default backfill is distinct from CREATE insert-default
persistence. Complex/binary defaults are not universally representable; non-finite
floating expressions need value-aware handling (M03/M04). The tested integer
evolved-default path does not qualify all defaults.

Local safety changes reject known non-finite/out-of-range temporal bounds during
binding and validate bounded live inline temporal payloads before FE constant
folding. Inline writing checks actual encoded instants before file creation or
replacement. Missing file bounds are still unknown, not a finite-domain proof.
TIMESTAMP_NS file min/max pruning is disabled until source-aware widening is
qualified; its lossy read mapping is unchanged. See
[read-safety validation](VALIDATION-DORIS-READ-SAFETY-2026-11-04.md).
