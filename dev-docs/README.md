# Developer documentation

The [project README](../README.md) covers usage and user-visible limits. These pages are
for people working on the connector. Dated validation reports, trials and superseded
designs live in [archive/](archive/README.md).

## Working on the code

- [DEVELOPMENT.md](DEVELOPMENT.md) — build, test tasks, source layout, what each level of testing proves.
- [DORIS-RUNTIME.md](DORIS-RUNTIME.md) — the pinned Doris commit, SDK and images, and how to re-vendor.
- [COMPOSE.md](COMPOSE.md) — the local FE/BE stack: smoke, deployment, rollback, network access.
- [CORPUS-REPLAY.md](CORPUS-REPLAY.md) — replaying the upstream DuckLake SQL corpus through Doris.
- [Duckbridge build-reuse handoff](HANDOFF-FOR-DUCKBRIDGE-DORIS-9fb3e3a2-API30.md) — exact API30 artifacts, commands and qualification limits.

## Behaviour and limits

- [TYPES.md](TYPES.md) — DuckLake ↔ Doris type mappings and their boundaries.
- [PGBOUNCER.md](PGBOUNCER.md) — metadata connection pool settings and running behind PgBouncer.
- [SAFETY-INVARIANTS.md](SAFETY-INVARIANTS.md) — the contracts that keep reads correct; do not loosen without a replacement proof.
- [UPSTREAM-SEAMS.md](UPSTREAM-SEAMS.md) — where the connector leans on Doris engine behaviour and what would need to change upstream.

## Open work

- [TODO.md](TODO.md) — priorities and the backlog for reads, writes, maintenance and research.
- [FINDINGS.md](FINDINGS.md) — the review findings register (stable IDs the backlog refers to).

Keep version numbers and qualification results in the archive's dated reports. When
work completes, remove its TODO entry and carry any remaining boundary into
SAFETY-INVARIANTS or FINDINGS.
