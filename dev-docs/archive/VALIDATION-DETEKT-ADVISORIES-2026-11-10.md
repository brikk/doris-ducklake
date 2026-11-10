# Advisory-only Detekt metrics — 2026-11-10

Policy change only; no production refactorings, line wrapping or control-flow
rewrites. The pinned Detekt version remains **2.0.0-alpha.3**.

## Configuration

These five rules remain enabled with rule-level `severity: warning`:

- `MaxLineLength`: 120 characters.
- `LongMethod`: 60 lines.
- `CognitiveComplexMethod`: complexity 15.
- `CyclomaticComplexMethod`: complexity 14.
- `ReturnCount`: 4 returns, excluding guard clauses and lambda returns.

The Gradle extension explicitly keeps `failOnSeverity = Error` and
`ignoreFailures = false`. All other rule settings and enforcement are unchanged.
The baseline was inspected: it already contains zero entries for these five
rules, so there was nothing to remove. Its unrelated exception-rule entry is
preserved. Advisories need no suppressions or baseline entries.

`AGENTS.md` documents the thresholds and readability-first guidance: do not make
metric-only edits; extract helpers only for meaningful responsibilities,
invariants or duplication. Do not mechanically reverse existing refactorings.
For older Detekt versions, verify their non-blocking-finding mechanism rather
than assuming the 2.x severity configuration works.

Pinned-version behavior was checked against
[Detekt 2.0.0-alpha.3 configuration/severity documentation](https://detekt.dev/docs/2.0.0-alpha.3/introduction/configurations/)
and the installed Gradle plugin API before validation.

## Build/Detekt-only verification

The build ran `assemble detekt` plus two temporary Detekt probe tasks, using the
actual production task's config, baseline, failure threshold and ignore-failures
setting. Probes and the init script live outside tracked sources.

- **Assembly: PASS.** No test task ran.
- **Repository Detekt: PASS.** All **209** current findings are visible
  `MaxLineLength` warnings in the console and XML/HTML reports.
- **Advisory-only probe: PASS.** Deliberately exceeded all five thresholds;
  exactly those five findings appeared, all with severity `warning`.
- **Unrelated error probe: expected FAILURE.** `EmptyFunctionBlock` remained an
  `error` and failed its Detekt task. The combined Gradle command therefore exits
  nonzero deliberately; this is the negative control, not a repository failure.
- The initial temporary probe had a mismatched package/directory, which correctly
  produced an unrelated `InvalidPackageDeclaration` error. After fixing only the
  probe's path, **only the two probe tasks** were rerun; successful assembly and
  repository Detekt results were reused.
- **No full test suites were run** for this policy change. Existing catalog
  adoption Java 25/17 results are retained without repetition.
- The pinned version already supports the requested behavior, so no Detekt upgrade
  is needed. Policy-only assembly retains the verified catalog 0.18.0 bundle and
  identity receipt byte-for-byte.

Evidence: `build/validation/detekt-advisory-policy/` contains `build-detekt.log`,
`probe-recheck.log`, per-probe checkstyle XML, `policy-probes.init.gradle` and
`summary.json`. The repository report remains at
`build/reports/detekt/detekt.xml` (and `.html`).
