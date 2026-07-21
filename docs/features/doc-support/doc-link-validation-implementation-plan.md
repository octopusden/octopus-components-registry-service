# Close the documented gaps in solution↔doc-component linking

## Context

The request asks for many-to-many linking between solution components and
documentation components. Investigation showed this repo already has an
opinion on the topic, recorded in `docs/features/doc-support/doc-improvement-concept.md`:

- **"Doc component referenced by many solutions"** already works today —
  `component_doc_links` has no uniqueness constraint on `doc_component_key`
  alone, and `ComponentDocLinkRepository.findByDocComponentKey` already
  returns a list. Nothing to build.
- **"One solution → several simultaneous doc components"** is *not* the
  intended model in this repo. The concept doc's own TODO #4 calls for
  "single doc component per version" validation, rule #6/#7 forbids doc→doc
  chains, and Part 2 puts multi-doc aggregation for a solution in a
  different system (idp-automation walks the solution's sub-components,
  each with its own single `doc` link, and aggregates their docs at release
  time). Zverev's answer on the ticket itself (2026-03-19) matches this
  exactly: single-solution/multi-doc "is not currently supported; this can
  be implemented via a transitive component."

Per decision, this plan **aligns with the documented design** rather than
building direct multi-doc-per-component support. The registry's job is to
make the existing single-doc-per-version model correct and well-validated,
not to add a capability the architecture deliberately doesn't want.
Concretely, three real gaps exist between the concept doc and the actual
code, all inside `component_doc_links` resolution/validation:

1. **Non-deterministic resolution.** `pickDocLink` (`EntityMappers.kt:1277`)
   does `links.firstOrNull { ... }` over `component.docLinks.toList()`, and
   `ComponentEntity.docLinks` (`ComponentEntity.kt:189`) has no `@OrderBy` —
   so when more than one row is a candidate, which one wins for the legacy
   v1-v3 `doc` field is undefined between requests.
2. **"Single doc component per version" isn't enforced.** The DB only
   dedupes exact `(component_id, doc_component_key, major_version)`
   triples, plus a partial index blocking two `major_version IS NULL` rows
   *for the same key*. Nothing stops two *different* `doc_component_key`
   values from both claiming the same `major_version` (or both `NULL`) on
   one component — exactly the ambiguity concept-doc TODO #4 asks to be
   validated away. `ComponentDetailMapperTest.docs_sortedBySortOrder`
   currently exercises this exact ambiguous shape (two different-key rows,
   both `majorVersion = null`) and will need a note once it's rejected at
   the service layer (the mapper itself doesn't validate, so the test
   stays green — see change #5).
3. **Doc→doc chains aren't blocked.** Concept-doc rule #6/#7: a
   documentation component can't itself reference another documentation
   component. No validator exists for this today. **Self-documentation is
   explicitly not a chain** — `validateDocComponentExistence` (`#20`)
   already treats a component referencing its own key as legal
   (`CrossComponentValidationTest.create_docComponentSelfReference_ok`),
   and the new chain validator must preserve that: a component whose only
   doc link points at itself is not "acting as a documentation component"
   for chain-detection purposes.

Scope boundaries (explicitly **not** doing these, and why):
- No DB migration — `component_doc_links` already has the columns needed.
- No new v4 endpoint — `docs[]` on `ComponentDetailResponse` already
  exposes the full per-component doc-link list.
- No change to the Groovy DSL — the `doc {}` grammar lives in the external
  `releng-lib` dependency, out of this repo's control; DSL-imported
  components stay single-doc via `ImportServiceImpl.kt:1467-1479`.
- Not implementing rule #5 (doc target must have `distribution.GAV`) or the
  `majorVersion` `$major`/`$minor` format validator — real documented gaps,
  but orthogonal to the multiplicity question this ticket raises; leaving
  them for a separate pass.
- Not touching the "one solution → several docs" aggregation flow at all —
  that's idp-automation's responsibility per the concept doc.

## Changes

### 1. Deterministic resolution — `EntityMappers.kt`

In `pickDocLink` (line 1277), sort `links` by `sortOrder` before selecting,
so `firstOrNull()` calls are deterministic regardless of JPA fetch order:

```kotlin
private fun pickDocLink(links: List<ComponentDocLinkEntity>, versionRange: String): ComponentDocLinkEntity? {
    if (links.isEmpty()) return null
    val ordered = links.sortedBy { it.sortOrder }
    val nullFallback = ordered.firstOrNull { it.majorVersion == null }
    val leadingMajor = Regex("""(\d+)""").find(versionRange)?.groupValues?.getOrNull(1)
    val matchByMajor = leadingMajor?.let { major -> ordered.firstOrNull { it.majorVersion == major } }
    return matchByMajor ?: nullFallback ?: ordered.firstOrNull()
}
```

Pure bug fix, no wire-shape change for well-formed data (single doc
component per version, the only case that should exist once change #2 below
is in place).

### 2. "Single doc component per version" validator — `ComponentManagementServiceImpl.kt`

Add `#30` next to the existing `#20` existence check
(`validateDocComponentExistence`, line 3019), called from the same two
sites (`createComponent`, line 374; update/patch, line 804):

```kotlin
/**
 * #30 single doc component per version — reject a docLinks set where two
 * DIFFERENT doc_component_key values would both apply to the same
 * major_version bucket (including the null/fallback bucket). Concept-doc
 * TODO #4: "single doc component per version."
 */
private fun validateSingleDocComponentPerVersion(entity: ComponentEntity) {
    val byMajor = entity.docLinks.groupBy { it.majorVersion }
    byMajor.forEach { (majorVersion, links) ->
        val distinctKeys = links.map { it.docComponentKey }.toSet()
        require(distinctKeys.size <= 1) {
            val label = majorVersion ?: "<no major version>"
            "docs: multiple doc components ($distinctKeys) claim major version " +
                "'$label' on component '${entity.componentKey}' — at most one is allowed"
        }
    }
}
```

### 3. Doc→doc chain validator — `ComponentManagementServiceImpl.kt`

Add `#31`, same call sites, using the existing
`ComponentDocLinkRepository.findByDocComponentKey`.

**Review correction:** an earlier draft of this validator checked
`it.docLinks.isNotEmpty()` / `findByDocComponentKey(...).isEmpty()`
directly, without excluding the entity's own self-referencing row. That
breaks `create_docComponentSelfReference_ok` (`CrossComponentValidationTest`,
2xx expected): a self-documenting component's own row is exactly what
`findByDocComponentKey(entity.componentKey)` returns, so the naive check
would 400 the very case `#20` was written to allow. Both directions below
are written to strip self-references before asking "does this component
have/receive doc links from someone **else**":

```kotlin
/**
 * #31 doc-component chain ban — concept-doc rule #6/#7: a documentation
 * component (one referenced by someone else's docLinks) cannot itself
 * declare doc links to a DIFFERENT component, in either direction:
 *  (a) a target this entity now references must not itself reference a
 *      third, different documentation component, and
 *  (b) if this entity is itself already referenced as someone else's doc
 *      target, it must not now be given doc links to a different component.
 * Self-documentation (a component's docLinks pointing only at its own key)
 * is explicitly NOT a chain — see #20's self-reference carve-out — so it
 * must not trip either direction below.
 */
private fun validateNoDocComponentChain(entity: ComponentEntity) {
    fun hasNonSelfDocLinks(componentKey: String, docLinks: List<ComponentDocLinkEntity>) =
        docLinks.any { it.docComponentKey != componentKey }

    val referencedKeys = entity.docLinks.map { it.docComponentKey }
        .filterNot { it == entity.componentKey }.toSet()
    if (referencedKeys.isNotEmpty()) {
        val chainedTarget = componentRepository.findByComponentKeyIn(referencedKeys)
            .firstOrNull { hasNonSelfDocLinks(it.componentKey, it.docLinks) }
        require(chainedTarget == null) {
            "docs: '${chainedTarget?.componentKey}' is itself a documentation component " +
                "and cannot be referenced by '${entity.componentKey}'"
        }
    }
    if (hasNonSelfDocLinks(entity.componentKey, entity.docLinks)) {
        val referencingThis = componentDocLinkRepository.findByDocComponentKey(entity.componentKey)
            .filterNot { it.component.componentKey == entity.componentKey }
        require(referencingThis.isEmpty()) {
            "docs: '${entity.componentKey}' is already used as a documentation component " +
                "by another component and cannot declare doc links of its own"
        }
    }
}
```

Both validators run post-flush, same as `validateDocComponentExistence`,
so self-reference edge cases behave consistently — including the existing
`create_docComponentSelfReference_ok` case, which must stay 2xx.

### 4. Requirements traceability

Add `SYS-066`, `SYS-067`, `SYS-068` to `docs/registry/requirements-common.md`.
**Review correction:** the original draft said "next after `SYS-064`," but
`SYS-065` was merged separately in the meantime (echo-safe field-override
save, commit `452183a`, already present at `requirements-common.md:2427`).
Re-check the tail of the file for the actual next free ID before landing
this — `SYS-066` is current as of this writing but may drift further if
other in-flight branches land first. Follow the existing template
(Priority / Test layer / Status / narrative), covering: deterministic
doc-link resolution, single-doc-per-version validation, and doc-chain
prohibition. Mark ✅ Tested once the corresponding tests below land, per
AGENTS.md's "every new feature → requirement first" / traceability rules
(test method names embed the SYS-NNN id, `@DisplayName` annotation).

### 5. Tests

- `DatabaseComponentRegistryResolverTest`: new case alongside existing
  `(6)/(6b)/(6c)` proving `sortOrder` (not insertion order) breaks ties.
  Note in-line why this constructs an ambiguous two-different-key shape
  directly on the entity: after change #2, that shape can no longer reach
  the DB through the API, so this test is defense-in-depth for rows
  written before the validator existed (no migration is planned to clean
  those up — see "Backward compatibility" below), not a shape the write
  path is expected to produce going forward.
- `CrossComponentValidationTest`: new 400 cases mirroring the `#20` doc
  existence tests —
  - two different doc components both with `majorVersion = null` → 400
  - two different doc components both pinned to the same `majorVersion` → 400
  - referencing a component that itself has doc links to a *different*
    component → 400 (chain, direction a)
  - giving doc links (to a different component) to a component already
    referenced as someone's doc target → 400 (chain, direction b)
  - **regression guard:** re-run (or extend)
    `create_docComponentSelfReference_ok` and add a PATCH-side equivalent —
    a component whose docs[] contains only its own key must stay 2xx
    both on create and on update, even after #31 lands. This is the test
    that would have caught the self-doc bug found in review; it must be
    green before/after #31 in the same PR, not just left as a pre-existing
    passing test.
- Update `ComponentDetailMapperTest.docs_sortedBySortOrder` fixture: it
  currently constructs two different-key rows sharing `majorVersion =
  null`, which is exactly the shape #30 now rejects at the service layer.
  Keep the mapper-level round-trip test (the mapper itself doesn't
  validate, only the service write path does) but note in-line that this
  input shape is no longer reachable through the API, or switch the
  fixture to distinct `majorVersion` values so it still represents a
  legal state. (`docLink_majorVersion_preserved` only ever constructs a
  single doc link — it doesn't exercise the ambiguous shape and needs no
  change.)

### 6. Backward compatibility

No wire-shape change, and no schema migration. This is a behavior-only
change scoped to two axes; treat them separately because their risk
profiles differ:

**Read path (change #1, `pickDocLink`) — strictly non-breaking.**
- For every component with ≤1 doc-link candidate in a given `majorVersion`
  bucket (i.e. all *new* writes after change #2, and the overwhelming
  majority of existing data, since the DB has always deduped exact
  `(component_id, doc_component_key, major_version)` triples), sorting by
  `sortOrder` before picking is a no-op — same link wins as before.
- For the narrow band of **pre-existing rows already in the ambiguous
  shape** (two different `doc_component_key`s sharing a `majorVersion`,
  written before this plan's validator existed — see below), the winner
  for the legacy v1-v3 `doc` field may change from "whatever JPA fetch
  order happened to return" to "lowest `sortOrder`, i.e. first-added." A
  caller polling the same component repeatedly could observe today's
  answer flip once. This is a fix, not a regression — the old behavior was
  never a promised contract, just accidental JVM/DB behavior — but flag it
  in the PR description so anyone who filed a support ticket about
  "inconsistent doc info" can be pointed at this cause.

**Write path (changes #2 and #3, the new `#30`/`#31` validators) — a
behavior change, not a wire change.** Requests that were previously
accepted (silently creating ambiguous or chained doc-link data) will now
get a `400` with the new error messages. This only affects:
- writes that set two different doc components on the same
  `majorVersion` bucket (or both `null`) — previously silently accepted;
  concept-doc TODO #4 always intended this to be invalid, it was just
  unenforced;
- writes that create a doc→doc chain — previously silently accepted;
  concept-doc rule #6/#7 always intended this to be invalid.
- Self-documentation (a component referencing its own key, with or
  without other doc links) is **not** affected by either new validator —
  see the self-doc carve-out in change #3 and its regression test in
  change #5. This is the one case that must be verified to keep behaving
  identically before and after this change.

**No backfill / no cleanup of existing bad rows.** Per the "no DB
migration" scope boundary, any component that already has the ambiguous
multi-doc-per-version shape in production keeps it — change #2 only
blocks *new* writes from creating more of it, and change #1 makes reads
of the existing bad rows deterministic rather than fixing them. If a
data audit turns up existing ambiguous rows, that's a separate cleanup
(likely a one-off admin PATCH per affected component, not a migration),
not blocking for this plan.

### 7. Compat verification

Both `pickDocLink` and the write-path validators sit on the v1-v3
read/write surface (`AGENTS.md:159-163`). Run
`scripts/local-stands/verify.sh` before declaring done; consult
`docs/registry/compat-gate.md` if it flags a diff. Expect no wire-shape
change for legal data — only previously-ambiguous/invalid combinations
change behavior (deterministic pick, or 400 on write).

## Impact on other components

This plan is scoped entirely to `components-registry-service`. No other
repo requires a code change to keep working, because the v4 `docs[]`
field is unchanged and the only behavior change is that some previously-
accepted (and already-wrong-per-spec) writes now 400. Reviewed explicitly:

- **`octopus-components-management-portal`** (the interactive writer,
  via its BFF) — **not verified from this repo; needs a check there, not
  a code change here.** If the portal has a doc-links edit form, confirm
  it surfaces the new `400` messages from `#30`/`#31` to the user instead
  of a generic "save failed." This is a UX-completeness check on the
  portal side, not a functional break — the portal's existing error
  handling for other 400s (e.g. `#20` doc-existence, already live today)
  presumably already covers the pattern; confirm rather than assume.
- **`releng-lib` / Groovy DSL import** (`ImportServiceImpl.kt:1467-1481`)
  — **no change needed, but the reason is narrower than it first looks.**
  `ImportServiceImpl` persists via `componentRepository.save(componentEntity)`
  directly (line 953) — it never calls `createComponent`/`updateComponent`,
  so `#20`/`#30`/`#31` don't run on the import path at all. That's *why*
  no existing import can start failing: the validators simply aren't
  invoked here, not that the shapes are unreachable.
  - `#30` (single doc component per version) is moot regardless, since
    each DSL component config produces exactly one doc link
    (`sortOrder = 0`) — there's only ever one candidate per component, so
    there's nothing for that validator to reject even if it did run.
  - `#31` (doc→doc chain) is a **real gap this plan doesn't close**: a
    DSL-imported chain (component A's config sets `doc { component = "B" }`,
    B's config sets `doc { component = "C" }` — each single-doc, so no
    conflict with #30) is not caught by the validators this plan adds,
    since import never calls them (whether `ImportServiceImpl` has any
    doc-chain check of its own was not verified here). Explicitly out of
    scope for this plan (the scope boundary already excludes DSL changes),
    but call it out as a known gap rather than implying the DSL is immune
    to it.
- **`idp-automation`** (owns Part 2 solution-doc aggregation per the
  concept doc) — **no change needed.** It walks a solution's
  sub-components and aggregates each one's single `doc` link at release
  time; this plan doesn't touch that flow or the model it depends on
  (single doc component per version), it only makes that existing model
  enforced and deterministic.
- **`components-registry-cli`** (`Component.kt`, `DocLinkResponse` mirror)
  — **no change needed.** It's a pure DTO mirror of the v4 response shape,
  which is unchanged; it does no client-side validation of doc-link
  multiplicity that this plan would make redundant or conflicting.

## Verification

1. `./gradlew :components-registry-service-server:test` — new + updated
   unit tests above, plus the existing `(6)/(6b)/(6c)` and `#20` suites
   stay green.
2. `scripts/local-stands/verify.sh` — v1-v3 compat baseline, per
   `docs/registry/compat-gate.md`.
3. Manual spot check via v4 API: create a component with two doc links at
   different `majorVersion`s (legal) and confirm 201; attempt the same with
   overlapping `majorVersion`s or a chained doc target and confirm 400 with
   the new error message.
