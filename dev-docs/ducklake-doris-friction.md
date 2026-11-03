# Current Doris integration seams

Runtime/build contract: [DORIS-RUNTIME.md](DORIS-RUNTIME.md). Open implementation
work and stable IDs: [REVIEW-FINDINGS.md](REVIEW-FINDINGS.md). Dated probes and
resolved reader/DEFAULT/count/nullability diagnoses are
[archived](archive/ducklake-doris-friction-through-2026-11-02.md), not current blockers.

## Correctness seams

- **Historical deletes (F08):** the reader applies positions without the DuckLake
  per-entry snapshot filter. Nullable partial-max metadata is not a safety proof.
- **Physical read views (F07):** a logical snapshot pin does not freeze files across
  flush/backdated compaction. Per-call metadata sessions are not a statement lease.
- **Per-file field identity (F11/F12):** scan-node dictionaries cannot represent all
  imported mappings. Native nested trees are complete; mapped/history/evolution
  limits remain. Reject unsupported cases instead of guessing field identity.
- **Pruning casts/types (F05/F06/F09/F26):** residual predicate execution cannot
  recover a matching file already pruned using erased casts or wrong ordering/domain.
- **Narrow-int writes (O05):** Iceberg's Arrow target and Doris source serde can
  disagree. Use INT/BIGINT; CREATE/descriptor success is not a write result.

## Transport and lifecycle

The connector emits native Parquet/Iceberg-shaped ranges, not in-JVM data pages.
Inline DATA/DELETE and metadata/system rows need a distributed row/payload transport
or a separately qualified scanner. The old shared-filesystem temp Parquet is a
development-only mechanism, not permission to stage arbitrary objects or remove them.
Review current SPI/JNI hooks at the exact pin before using old missing-hook claims.

`table_format_type="iceberg"` is a load-bearing dispatch alias; an honest DuckLake
discriminator needs engine support. `file_format_type=parquet` is scan-level.
Credentials are emitted as `s3.*` and BE-canonical `AWS_*`; maintenance has its own
narrower client. Scan-wide and per-range thrift ownership must stay distinct.

## Deployment and SPI ergonomics

- Plugin root: `${DORIS_HOME}/plugins/connector/<name>` (singular). The plural
  `plugins/connectors` belongs to a different bridge; never clear it as cleanup.
- FE-local readiness uses `SHOW FRONTENDS`. `SELECT 1` can require a live BE and
  deadlock a BE-startup dependency on FE health.
- JVM flags come from `fe.conf`; container environment alone does not override
  the start script. Runtime priority-network bytes/election identity must survive restart.
- Doris injects catalog properties. Validate required keys without rejecting every
  unknown key; procedure args are a separate closed-contract issue (M12).
- Per-catalog close must not deregister shared JDBC drivers (N06).
- Upstream discoverability of `connector_plugin_root` remains an ergonomics ask,
  not a missing local FE patch or permission to apply the archived patch.

Current install/recovery/network instructions are in [COMPOSE.md](COMPOSE.md).
