# Agent Guide

## Detekt advisories and readable code

Prioritize readable, cohesive code over metric compliance. These five enabled
Detekt rules are review signals, not mandatory limits or build/CI gates:

- `MaxLineLength`: 120 characters.
- `LongMethod`: 60 lines.
- `CognitiveComplexMethod`: complexity 15.
- `CyclomaticComplexMethod`: complexity 14.
- `ReturnCount`: 4 returns, excluding guard clauses and lambda returns.

This repository uses Detekt 2.x: each rule has `severity: warning`, while the build
failure threshold stays `Error` and `ignoreFailures` stays false. Findings remain
visible in Detekt reports; all other rules retain their existing enforcement.
Advisories require neither suppressions nor baseline entries. Do not add baseline
entries for these five rules. If using an older Detekt version elsewhere, verify
its non-blocking-finding mechanism rather than assuming the 2.x configuration works.

Do not wrap lines, split methods, add trivial helpers, introduce mutable state, or
rewrite clear control flow solely to clear an advisory. Extract helpers when they
express meaningful responsibilities, isolate invariants, or remove duplication.
Do not mechanically undo existing refactorings: this is policy, not a production
behavior change.

## Commit identity (Buzz agents)

When you (a Buzz agent) commit on someone's behalf, attribute the commit to the
**human who issued the request**, not to the machine's global git identity.

1. The requester is the signed author pubkey of the triggering Buzz event.
2. Look that pubkey up in [`.buzz/authors.toml`](.buzz/authors.toml) to get the
   git `name` / `email`.
3. Commit as that identity, crediting the model as a co-author:

   ```bash
   GIT_AUTHOR_NAME="<name>" GIT_AUTHOR_EMAIL="<email>" \
   git commit -m "<subject>

   Co-Authored-By: Claude <model> <noreply@anthropic.com>"
   ```

4. **Pubkey not in `.buzz/authors.toml`?** Stop and ask — never silently fall
   back to the machine's global identity. Add the person (reviewed) first.

This matters for a **shared** Buzz agent that acts for several people. A person's
own standalone agent that only ever commits as its owner can ignore it — that
agent's global git identity is already correct.
