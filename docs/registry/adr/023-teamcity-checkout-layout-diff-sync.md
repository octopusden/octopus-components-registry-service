# ADR-023: TeamCity → registry VCS checkout layout as two manual jobs, Diff and Sync

## Status

Accepted. Implemented in #510; [SYS-102](../requirements-common.md); design in
[technical-design §6.10](../technical-design.md#610-teamcity-checkout-layout-diffsync-onb-002).

## Context

[ADR-022](022-vcs-root-checkout-layout.md) gives the registry the fields that place a
component's VCS roots: Source Path, Checkout Directory, Build Working Directory. Existing TeamCity
chains already encode that checkout layout by hand, and the `V8__` back-fill only guessed it from
registry order. Existing chains hold the real values for multi-root rows and for single-root chains
with a Checkout Directory or a non-default `WORK_DIR`.

A one-off script would cover the population once, with no report a non-engineer can open, no
selective apply and no repeatable "has this drifted" check.

## Decision

1. **Two system jobs, started manually from the Portal admin page.** No schedule.
   - **Diff** (TeamCity ↔ registry, read-only) produces a report as JSON, HTML and CSV. Every run
     has an id. Only the last completed result is kept, in memory of the pod that ran it; it stays
     readable while a new Diff runs and is replaced when that one completes.
   - **Sync** (TeamCity → registry only) takes `{diffId, componentIds}`. If `diffId` is not the
     last completed Diff, the whole request is refused — 409
     `{"errorMessage":"diff replaced, re-run Diff","errorCode":"checkout-layout-diff-stale"}` — and
     nothing is written, so a Sync always acts on the report the user looked at. Otherwise it
     writes only the named components whose status in that Diff is `RESOLVED`.
2. **The jobs reuse the existing admin-job pattern**: a `*JobService` per job, the
   `MigrationLifecycleGate` single-job mutex, polling, the Events feed and the `IMPORT_DATA`
   permission, like TeamCity resync and validation. They read the component ↔ TeamCity project
   links resync writes, and the validation job's build-type fetch extended with
   `vcs-root-entries(checkout-rules, vcs-root(url))`, with the existing read-only TeamCity user.
   Endpoints under `/rest/api/4/admin/teamcity-checkout-layout`: `POST /diff`, `GET /diff/job`,
   `GET /diff/report.{json,html,csv}`, `POST /sync`, `GET /sync/job`, `GET /sync/report.csv`. Deprecated
   aliases under `/teamcity-placement` return the new codes; they are removed once the Portal uses the
   new paths in every environment.
3. **Derivation** assigns each component exactly one status, checked in this order: `TC_ERROR`,
   `ROOTS_MISMATCH`, then the derivation outcomes (`NO_CHAIN`, `COMPILE_PAUSED`,
   `OUTSIDE_TEMPLATES`, `UNEXPRESSIBLE`, `CONFLICT`), then `IN_SYNC`, `INVALID`, `MANUAL_EDIT`,
   `RESOLVED`.
   - Compile configurations (based on the compile templates configured in
     `teamcity.validation.{gradle,maven}-build-template-id`, not paused) are authoritative.
   - A registry entry matches a TeamCity VCS root entry by the full repository URL, host included
     (Git case-insensitive, trailing `.git` and `/` ignored), never by name or path alone.
   - Checkout rule → fields: `+:.=>d` gives Checkout Directory `d`; `+:sp=>d/sp` gives Checkout
     Directory `d` and Source Path `sp`; `+:sp=>sp` or `+:sp` gives Source Path `sp`; no rule
     gives the checkout root; anything else is `UNEXPRESSIBLE`. `WORK_DIR` minus
     `%teamcity.build.checkoutDir%/` gives the Build Working Directory.
   - A value counts only when every compile configuration attaching the repository agrees,
     otherwise `CONFLICT` (also when one repository is attached twice with different rules).
   - A component whose derived values already match the registry is `IN_SYNC`. One that differs
     but fails the registry's own validation (ADR-022) is `INVALID` and never offered to Sync.
   - **`ROOTS_MISMATCH`**: the compile configurations attach a repository the registry does not
     list (typically a shared tooling repository). It is reported, never written, and is also a
     TeamCity validation warning (`VCS_ROOTS_DIFFER_FROM_REGISTRY`). Sync never adds, removes or
     changes a VCS root: it writes back the registry's own roots and changes only their checkout layout
     fields. Adding the shared repository to the registry is not a fix: release tooling
     attributes commits by each root's `vcsPath`, so every listing component would inherit its
     commits.
4. **Scope.** Base rows of non-archived components that have a TeamCity version-line link:
   multi-root rows, and single-root rows whose chain has a Checkout Directory or a non-default
   `WORK_DIR`. Archived components and per-range `vcs.settings` rows are left out of the report.
   Only `RESOLVED` is ever written.
5. **Writes go through the v4 write path**: validation, the name derivation of ADR-022 decision 4,
   and audit.
   - **Provenance.** A checkout layout field is overwritable in exactly two cases: no audit record has
     ever touched it (null, or the `V8__` back-fill, which wrote no audit record), or the last
     audit record that touched it is a previous Sync's. When the last audited change is a user
     write and differs from the derived value, the component is `MANUAL_EDIT`: kept, reported,
     never overwritten.
   - **Reserved comment.** Every Sync write carries the change comment
     `sync from TeamCity (job <id>)`. A user write whose comment starts with `sync from TeamCity`
     is rejected (400), so provenance cannot be forged.
   - **"Changed since diff".** Sync re-reads TeamCity and the registry for each named component
     just before writing and skips it, reported as `changed since diff`, if either differs from
     the Diff snapshot.
   - **Rollback** is not an endpoint: the Sync result lists the before and after value of every
     written field, and technical-design §6.10 ("Rolling back a Sync") describes reverting a run
     by its job id through v4.
6. **Permissions.** Starting either job needs `IMPORT_DATA`. Reading the last Diff report needs
   only component-view access.
7. **Rollout.** QA first: Diff, Sync a few components, Diff again to confirm them `IN_SYNC`.
   Production after the Portal that edits these fields is released there.
9. **Out of scope:** a scheduled sync, Diff history in the database, writes back to TeamCity,
   adding or removing roots, the template parameter of ADR-022 decision 9.

## Alternatives considered

- **A recurring sync with TeamCity and the registry as dual masters.** A hand-edited chain would
  silently overwrite a deliberate registry edit, reintroducing the drift ADR-022 removes.
- **Logic in the Portal backend.** The Portal has no job framework or TeamCity client; building
  both would duplicate what the registry has and split "reads TeamCity, writes registry" across
  two services.
- **Direct database writes.** Bypass validation, name derivation and audit.
- **A one-off script, extended to single-root rows.** No UI, no selective apply, no repeatable
  check.

## Consequences

- Positive: checkout layout of the single-root Checkout Directory and `WORK_DIR` cases becomes
  importable through a reviewed, permissioned flow, and Diff can be re-run at any time.
- Positive: no new job framework, TeamCity client or permission model.
- Negative: no Diff history; the last report is lost on a pod restart and is per pod.
- Negative: Sync is one-way and manual; a component that drifts again needs another Diff → Sync.
- Negative: a "changed since diff" skip makes a Sync do less than selected; the result lists the
  skipped components.
- Residual: `UNEXPRESSIBLE`, `CONFLICT`, `NO_CHAIN`, `OUTSIDE_TEMPLATES` and `COMPILE_PAUSED`
  components are reported only.
- Risk: `ROOTS_MISMATCH` invites the wrong fix (decision 3). The warning is informational; the
  open choice is between leaving it, detaching the repository in TeamCity, or teaching the
  comparison a list of known tooling repositories to ignore.
