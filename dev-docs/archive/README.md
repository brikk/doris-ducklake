# Retained development history

These files preserve diagnoses, completed checklists, previous engine/catalog
qualification, handoffs and designs. They are **not current installation/build
instructions**, and their counts/hashes describe only their stated artifacts.
Current developer docs start at [../README.md](../README.md).

## Retention map

- `FE-PATCHES.md`, `ducklake-fe.patch`: former FE patch and re-vendor chronology;
  the current FE builds patch-free.
- `TODO-*-through-2026-11-02.md`, `PLAN-through-2026-11-02.md`: old working logs,
  including completed tasks. Open work is carried into current TODOs/findings.
- `REVIEW-2026-09-05.md`: original stable F/M/N/O/S/W/V/L IDs, evidence and resolutions.
  Use [the current register](../REVIEW-FINDINGS.md) before acting on old diagnoses.
- API24/API26/catalog validations and handoffs: retained old artifact identities
  and rollback context, not API27 qualification.
- F22/F23/F24/F25 validations and `TODO-F24-PLUGIN-PROVENANCE.md`: completed
  ownership/network/provenance/correctness work and its exact acceptance limits.
- R07 validations and `R07-QUALIFICATION-API13.md`: historical local-candidate
  campaign. Its retired repository override does not work in the current build.
- Native nested/publication reports: initial reproductions, before/after controls
  and producer hashes; current API27 results are in the maintained qualification.
- Original design/sanity/SPI/friction/research docs: useful prior mechanics and
  failed approaches, not promises that old gaps still exist or are all solved.

Archive retention does not authorize deletion/migration of any SDK, image, volume,
test fixture, raw evidence or shared checkout. Some links name sibling repositories
or machine-local evidence that is not present in a standalone clone. Dated source
line links are historical references, not current line-number guarantees.
