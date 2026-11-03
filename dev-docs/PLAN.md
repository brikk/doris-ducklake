# Development priorities

Current baseline and supported workflow: [DEVELOPMENT.md](DEVELOPMENT.md).
This plan orders work; it does not authorize a new runtime, deployment or corpus
campaign. Detail lives in the open TODOs and [findings register](REVIEW-FINDINGS.md).

## 1. Protect data and close silent wrong-result paths

Prioritize maintenance authority (F02), stale destructive handles (F20), coherent
physical read views (F07), historical deletes (F08), pruning semantics and imported
field identity. Prefer explicit refusal over a speculative semantic bridge.

Exit: distinguishing cross-engine regressions prove the affected behavior,
including a failing pre-fix control; source inspection alone is not live closure.

## 2. Complete the bounded Parquet read contract

Read-side correctness comes before richer writes. Keep default/candidate lanes
separate. Distributed inline transport, nested mapping/evolution/history and broader
final-1.1 lifecycle/lease/CDC gates remain open. Grow replay coverage only with
measured mirrors and reviewed exclusions. See [TODO-read.md](TODO-read.md).

Exit: supported read shapes return exact values across writes/deletes/evolution,
and unsupported shapes fail clearly; no all-skipped or inherited green acceptance.

## 3. Harden and extend Parquet writes

Resolve current commit/schema/stats/partition contracts before admitting row DML,
overwrite, sorted writes, ALTER and compaction. Preserve single-snapshot atomicity
and cross-engine metadata semantics. See [TODO-write.md](TODO-write.md).

Exit: Doris output and catalog metadata agree with DuckDB at value/partition/stat
boundaries, including concurrent DDL and failure recovery.

## Ongoing upkeep

Review upstream master and release branches without automatically re-vendoring.
For an authorized bump, use the [runtime contract](DORIS-RUNTIME.md), qualify exact
SDK/FE/BE/JNI/bundle bytes and retain rollback. API equality alone is insufficient.
Do not auto-enable capabilities or overwrite shared staged work/retained stacks.

Function execution pushdown and DuckDB-native `.db` reads are architecture non-goals,
not unfinished tasks. Other formats are an unscheduled feasibility decision in
[TODO-research.md](TODO-research.md), not a promised next phase.
