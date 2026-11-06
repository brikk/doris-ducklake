# Safety invariants and qualification limits

Current default: catalog 0.14.0 on the exact [API27 runtime](DORIS-RUNTIME.md).
DuckLake 1.1 catalogs are refused; the experimental 1.1 reader trial is [archived](archive/CATALOG-CANDIDATE.md).
This inventory records contracts to preserve, not a production-clean verdict.

## Native reader bridge

- Ranges deliberately use Iceberg-shaped thrift/`table_format_type="iceberg"` to
  reach the native reader. This transport choice does not prove DuckLake semantics.
- Never set top-level `TIcebergFileDesc.content` to position-delete/deletion-vector
  values 1/3 on data ranges: the BE routes those to a delete system-table reader.
  Delete descriptors belong in the per-delete-file list.
- Emit `file_format_type=parquet` at scan-node level; range format alone is insufficient.
- Scan-wide schema/credentials and per-range file/delete/path values have different
  ownership. Test with engine-prepopulated thrift, not just empty descriptors.
- Current native nested reads require a **complete parent-aware field-ID tree**.
  Scalar-only dictionaries can turn populated nullable nested roots into NULL.
  Preserve depth/node/ID/arity validation and unsupported mapping/history refusals.

## Default refusals and incomplete semantics

- Inline DATA is off by default; the experimental scalar temp-Parquet path requires
  shared local filesystem access. It is not a distributed transport. Concurrency,
  namespace, timestamps and cleanup have remaining findings (M09/M10).
- Inline DELETEs refuse. Partial data-file history requiring a hidden snapshot
  filter refuses. Historical scans with delete files now also refuse (F08), including
  NULL advisory bounds; actual per-position snapshot filtering remains unavailable.
- COUNT/LIMIT metadata shortcuts only apply under their narrow eligibility gates;
  filters, deletes, inline/partial state or nonlatest history require normal scans.
- Imported embedded field IDs are not universally authoritative (F11). A conflict-aware
  scan-node name union cannot express every per-file mapping/evolution (F12/O04).
- Mapping descriptors are not value/round-trip proofs. Nanoseconds lose precision;
  int128 does not wholly fit DECIMAL(38,0); unsigned read widening is not write-domain
  enforcement. See [TYPES.md](TYPES.md).
- New API27 write-only-default and timestamp semantic markers stay unadopted.

## Maintenance, lifecycle and credentials

- Keep managed-residue/age/reference/raw-key guards. F01's scheme-alias repair
  does not authorize key normalization, wider deletion or bypassing SDK-rewritten-key
  refusals. [F01 evidence](VALIDATION-F01-2026-10-31.md).
- Catalog-wide maintenance is not authorized merely by ALTER on the named table (F02).
  Explicit snapshot IDs bypass age floors; retention is not a physical reader lease.
- Register JDBC drivers under the shared plugin classloader. Catalog close closes
  its pool, not the shared driver registration. Deregistration requires an unload hook.
- S3/AWS credential aliases for the BE do not establish maintenance client parity
  (M11) or a credential-isolated tenant boundary.

## Tests and operations

- One parked inline-delete test stays disabled. Corpus assumptions/skips are not
  successful coverage; count actual mirrors and keep unsupported reasons explicit.
- Real `parquet-format-structures` precedes the fat `fe-thrift` on test classpaths.
  That test repair is not qualification of the dev inline writer in the FE's host
  Parquet classpath.
- Unit tests, static nine-plugin/six-layout checks and native-S3 runtime tests have
  different scopes. Lazy native S3 with an uninitialized JNI registry does not test
  every Java plugin or Duckbridge JDBC/Quack.
- Full smoke passes require exact typed assertions and current owner/build/runtime
  receipts, not a printed completion line. See [COMPOSE.md](COMPOSE.md).
- Loopback publication plus authenticated SSH is development containment, not Doris
  tenant authorization or proof of host-firewall reachability. Never infer internet
  exposure solely from a retained all-interface binding.
- No automatic legacy-container migration, hot reload, global cleanup sweep or
  metadata reset is an upgrade/recovery mechanism.

Original diagnoses and resolved test workarounds are retained in
[the archived inventory](archive/CRUTCHES-AND-SHORTCUTS-through-2026-11-02.md).
