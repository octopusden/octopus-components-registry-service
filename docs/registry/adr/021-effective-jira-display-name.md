# ADR-021: The Jira display name falls back to `componentDisplayName`, at read time only

## Status

Accepted. Implemented on the DB resolver path (`EntityMappers.buildJiraComponent`).

## Context

A component carries two independent display-name fields:

| Field | DSL | Column | Meaning |
|---|---|---|---|
| `displayName` | `componentDisplayName` | `components.display_name` | the general/product name |
| `jiraDisplayName` | `jira { displayName = … }` | `components.jira_display_name` | a Jira-specific name |

`JiraComponent.displayName` was sourced from `jiraDisplayName` alone. The downstream Jira
release plugin renders that value as the release-notification subject and the release-note
header, and when it is blank falls back to the **Jira project name**, which every component
in that project shares.

`jira { displayName }` is opt-in and most configs never set it, so most components rendered
a shared project name instead of the name they already had. Registry data (998 components):

| | count |
|---|---|
| `componentDisplayName` only — affected | **518** |
| `jiraDisplayName` only | 83 |
| both | 190 (**110 of them differ**) |
| neither | 207 |

The 110 divergent pairs matter: Jira-specific names are common, not a rare override, so
collapsing the two fields into one — or making either win unconditionally — would be wrong.

This is a config-completeness gap, not a coupling bug: the two fields are written and
validated independently, and no code path copies or clears one from the other.

## Decision

**Effective display name — prefer the name specific to the context, fall back to the other,
then to the component key where the field cannot be null.**

| Surface | Resolves to | Nullable |
|---|---|---|
| Jira: `JiraComponentDTO.displayName`, notification subject, release-note header | `jiraDisplayName ?: displayName` | yes |
| `DetailedComponentVersion.component` (non-null `String`) | `jiraDisplayName ?: displayName ?: componentKey` | no |
| General display surfaces | `displayName ?: jiraDisplayName` | yes |

The priority flip between the Jira rows and the general row is the rule, not an
inconsistency: each context prefers its own name and falls back to the other. Null is
preserved on the nullable surfaces so the downstream plugin keeps its project-name fallback
for components that have no name at all.

Applied once, in `EntityMappers.buildJiraComponent` — the single join site every v2 and v4
Jira surface routes through (`DatabaseComponentRegistryResolver` only re-wraps its output to
add the hotfix flag).

### Resolution is a rendering concern

Two kinds of surface are **excluded**, and the general-surface row above is **not yet
implemented** (see Consequences):

1. **The legacy v1/v2/v3 `$.name`** (`EntityMappers` → `Component.name` / `ComponentV1`)
   keeps serving `display_name` verbatim. Resolving it would flip 83 components from `null`
   to a string against the 2.0.87 compat baseline — the failure mode that produced 6399 diffs
   on the first migration attempt and led to the current "nullable, verbatim, never
   backfilled" rule.

2. **Every write-back surface**: the V4 detail/summary responses the Portal's edit form binds
   to, the Jira editor field, and the as-code DSL export. A resolved value in an edit form is
   persisted into the column on the next unrelated save — which breaches exclusion 1 one
   component at a time, emits `componentDisplayName = …` lines nobody authored, and can fail
   the `display_name` UNIQUE constraint with a 400 on an edit that never touched a name.

> **The principle: any surface a value can be written back through exposes the stored column
> unchanged. Read-only display surfaces resolve.**

## Alternatives rejected

- **Fix it downstream, in the release plugin's `getDisplayName`.** Patches one caller and
  leaves the `jira-component` endpoint and every other consumer still serving `null`.
- **Fall back further, to the component key.** Would give the 207 nameless components a raw
  key in a customer-visible subject line. That is a product decision, not a bug fix; `null`
  plus the plugin's existing project-name fallback is the documented status quo.
- **Backfill `display_name` from `jira_display_name`** for the 83. Rejected: it writes to the
  byte-compat column (exclusion 1), and the values are not unique where the column is — three
  collision clusters exist in production data (one name wanted by two live components, one by
  four archived ones, and one already owned by a different component). Rendering achieves the
  visible outcome without touching the data or resolving those collisions.
- **Apply the same fallback in the Groovy/DSL loader** so both sources agree. Rejected:
  `known-deltas-git.json` is intentionally empty and encodes the deploy-without-migration
  no-op invariant, enforced by the TeamCity `[2.3]` git-mode gate. Changing that loader makes
  a git-mode candidate diverge from the 2.0.87 baseline by construction, and the file
  explicitly rules out suppressing such a diff. The git resolver retires at the prod cutover;
  the invariant is load-bearing until then.

## Consequences

- **518 components** change the name they render in release notifications, release notes and
  the v2 `jira-component` surface — from a shared Jira project name to their own. This is the
  intended outcome.
- **`DetailedComponentVersion.component` changes for those same 518**, from the component key
  to a display name. The field already read `displayName ?: componentName`, so it was already
  a label for the 273 components with a `jiraDisplayName`; this makes it consistently a label.
  No consumer in any repository reads it — the heaviest consumer of `detailed-version` reads
  only the version fields, and passes component identity as a separate argument. An external
  HTTP consumer treating it as an identifier would break silently; that risk is accepted here
  and recorded rather than paid for by threading the raw column through the mapper.
- **The compat gate reports intentional deltas** on `component.displayName` for the affected
  components in db-mode. Recorded in `known-deltas-db.json`; `known-deltas-git.json` stays
  empty, so the no-op invariant is untouched.
- **The v4 contract does not change** — no v4 controller exposes the Jira DTOs, and
  `/rest/api/4/versions/preview` renders from a synthetic placeholder component. No
  `api-changelog` entry, no OpenAPI regeneration.
- **Grouping improves rather than degrades.** The release plugin uses the resolved name as the
  map key for issues and CRN/CUN buckets across a component and its dependencies, and compares
  it by string equality to split "own component" from "dependency". Today 293 components across
  29 projects share a project name and therefore collapse into one bucket; `display_name` is
  UNIQUE, so the fallback strictly reduces those collisions.
- **The general-surface row is not yet implemented.** It is what would give the 83
  `jiraDisplayName`-only components a visible name without any write, and it lands as a
  follow-up because it is a different surface and a disjoint population.
