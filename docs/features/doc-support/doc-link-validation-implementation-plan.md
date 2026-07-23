# Support many-to-many component ↔ doc-component links

## Top-level design

A component can reference a "documentation component" — another regular
component holding its docs — via `component_doc_links`
(`component_id`, `doc_component_key`, `major_version`, `sort_order`).
The intended model going forward: **any component may hold several
simultaneous doc links at once.** This is genuine many-to-many, not "one
active doc per version."

This capability already works today, with zero code changes needed to
enable it: the write path already accepts an uncapped list of doc links
with no dedup-by-key restriction, the read path already returns the full
list, and the database schema only blocks an exact duplicate
`(component, doc key, major version)` triple — it never enforced "at most
one doc key per version." So this isn't "build a feature" — it's "close
the two real gaps around an already-working capability, and document it
as an intentional, tested contract":

1. **Deterministic legacy resolution.** There's an older, single-valued
   API surface that can only ever expose one doc link per component (it
   predates the many-to-many model). When a component has multiple doc
   links, which one this older surface picks needs to be a documented,
   deterministic rule — not accidental database-fetch ordering. Multiple
   candidates is now the normal case, not rare bad data, so this
   resolution rule matters more than it used to.
2. **Doc→doc chains stay blocked.** A documentation component must not
   itself reference another documentation component. This is orthogonal
   to multiplicity — it's about a *different* component being both a doc
   target and a doc referencer — and needs a validator on the direct-API
   write path. Self-documentation (a component referencing only its own
   key) must stay legal throughout — that's an existing, explicitly
   tested contract that must not regress.

### Scope boundaries

- **No "single doc per version" restriction** — two different doc
  components sharing a version bucket (including no version specified)
  is intentionally legal. Nothing to build or remove here — the database
  and the primary write API already allow it.
- **No friendly duplicate-pair guard** for the exact same doc
  link submitted twice in one write — the database already rejects that
  with a clear error today. Leaving this as-is by explicit decision: it's
  a UX nicety, not part of this request, and the bad state is already
  prevented.
- **No change to the Groovy DSL / config-import grammar**, by explicit
  decision. That grammar lives in this repo's own DSL/config-loading
  module and is genuinely single-valued *per version-range config* today
  — a component authored via DSL can already have different docs across
  different version ranges, but not several at once within one range.
  Extending that is a real, larger, self-contained change (grammar +
  its validator + the import mapping, plus its own compatibility-test
  surface) — deferred as a follow-up. The primary write API and the
  admin UI already deliver full many-to-many today; DSL-authored
  components keep one-doc-per-range until that follow-up lands.
- No database migration — the schema already permits true many-to-many.
- No new API endpoint — the current multi-value API surface already
  exposes the full list.
- **Only the chain-ban rule (#7) is restored to the direct-write API in
  this pass.** The DSL validator also enforces rule #5 (a doc target must
  have `distribution.GAV`), which the direct-write API does not — that
  parity gap is real but orthogonal to multiplicity and deliberately left
  for a separate pass. Rule #6 (non-overlapping doc ranges) is not
  restored because the reversal obsoletes it (see change 3).

## Backward compatibility

- **Write path**: two different doc components sharing a version bucket
  was never actually rejected in production — no regression to reason
  about, only a newly-documented, intentionally legal capability. The
  only new write-path rejection is the chain ban. Because the chain ban
  never existed on the direct-write API before, production data may
  already contain a doc→doc chain; after this lands, a patch that trips
  the `crossComponentRelevantChange` guard on such a component will 400
  until the chain is broken. This mirrors how `#20` already behaves for a
  component whose docs reference a since-deleted target — consistent with
  existing behavior, not a new class of surprise, but worth a PR note.
- **Read path**: a no-op for single-candidate components; makes the
  older single-valued surface's answer deterministic instead of
  undefined for genuinely multi-doc components — now the normal case, so
  more likely to be *noticed* than when it only affected rare bad data.
  Worth a one-line PR callout: any caller who observed "flip-flopping"
  values on that older surface will see a stable answer after this lands.
- No database migration, no wire-shape change on the current write/read
  API.

## Impact on other components

All four candidates were checked directly (not just grepped-for-and-
assumed) — either in this repo, in a local sibling checkout, or via a
fresh clone. Findings:

- **CLI client** — no change; it's a pure DTO mirror of the current API
  response shape, which is unchanged.
- **This repo's own DSL/config-loading module** — no change *in this
  change*, by explicit decision (see Scope boundaries above). This is
  the one place a real, in-repo follow-up exists: its `doc` field is
  singular per version-range, and its validator already runs
  chain-ban + existence + distribution-artifact checks for DSL-authored
  configs at load time — genuine multi-doc-per-range for DSL-authored
  components needs its own future change there, separate from this one.
- **Admin/management UI (portal)** — checked locally — **no change
  needed, confirmed.** Its component editor already fully supports
  multiple simultaneous doc links: a repeatable field-array control with
  per-row add/remove, and its save requests already submit the
  multi-value array shape end-to-end.
- **Release-engineering build-parameter tooling** — checked the
  candidate repo locally — **not the right repo, confirmed.** It has zero
  references to documentation-link handling or any client of this
  service; whatever computes build-time doc-dependency parameters (if it
  exists at all) could not be located in any locally-available repo. No
  action item since nothing concrete was found to affect.
- **Solution-release aggregation tooling** — cloned fresh and inspected
  — **the aggregation feature this was expected to own does not exist in
  that repo.** Its entire source is a handful of files implementing one
  unrelated CLI command for TeamCity build-chain restructuring; it calls
  this service's client only for two unrelated fields, never for
  anything documentation-related. There's nothing there to break because
  the feature was never built. Worth flagging to whoever owns that
  system's roadmap, since it describes a capability that doesn't exist
  yet, independent of this change.

## Implementation

### 1. Deterministic resolution — `EntityMappers.kt`

In `pickDocLink` (`EntityMappers.kt:1277`), sort `links` by `sortOrder`
before selecting, so the result is deterministic regardless of JPA fetch
order:

```kotlin
/**
 * The legacy single-valued `doc` field (Component.kt / DocDTO) is
 * structurally single-valued on the wire even though a component may
 * legitimately hold N simultaneous doc links (docs[], uncapped — see
 * ComponentManagementServiceImpl.addDocLinks). This is the documented,
 * intentional single-value projection policy for that legacy surface,
 * not a fallback for bad data: sort candidates by sortOrder
 * (author-declared precedence), then prefer an exact majorVersion match,
 * else the null/fallback row, else the first remaining by sortOrder.
 */
private fun pickDocLink(links: List<ComponentDocLinkEntity>, versionRange: String): ComponentDocLinkEntity? {
    if (links.isEmpty()) return null
    val ordered = links.sortedBy { it.sortOrder }
    val nullFallback = ordered.firstOrNull { it.majorVersion == null }
    val leadingMajor = Regex("""(\d+)""").find(versionRange)?.groupValues?.getOrNull(1)
    val matchByMajor = leadingMajor?.let { major -> ordered.firstOrNull { it.majorVersion == major } }
    return matchByMajor ?: nullFallback ?: ordered.firstOrNull()
}
```

Update `docs/registry/schema-spec.md` (~line 401, the `doc:
{component, majorVersion}` row) to add the sortOrder tie-break clause —
it currently documents majorVersion-match/null-fallback but is silent on
ties, which becomes a real spec gap once multi-doc is the normal case.

### 2. Doc→doc chain ban validator — `ComponentManagementServiceImpl.kt`

**This is a parity restoration for the direct-write API, not a net-new
rule.** The DSL/config-loading module's validator already enforces this
exact rule ("Doc component ... must not have 'doc' property",
`EscrowConfigValidator.groovy:543`) for DSL-imported/reloaded components,
since its config loader instantiates and runs it at config-load time
(`EscrowConfigurationLoader.groovy:218`, wired into `ImportServiceImpl`).
The direct-write API path (`ComponentManagementServiceImpl`) doesn't get
DSL validations for free — per the existing validation-parity pattern
already in this file (`:3550-3557`, restoring checks "the [direct] write
path dropped"), each DSL rule needs its own reimplementation to apply to
direct writes. This is that reimplementation, mirroring how the existing
`#20` doc-existence check already restored parity for that rule.

Add next to `#20` (`validateDocComponentExistence`, line 3045), and wire
the call at the same two post-flush sites, immediately after the `#20`
call: `createComponent` (line 374) and update/patch (line 804). On the
patch path, the `#20` call sits **inside** the `if
(crossComponentRelevantChange)` guard (line 801) — place the chain-ban
call inside that same guard, so it inherits the existing grandfathering:
a component is only re-validated for chains when the patch actually
touches docs or another cross-component-relevant field, never on an
unrelated field edit. Uses the existing
`ComponentDocLinkRepository.findByDocComponentKey`:

```kotlin
/**
 * #30 doc-component chain ban: a documentation component (one referenced
 * by someone else's docLinks) cannot itself declare doc links to a
 * DIFFERENT component. Orthogonal to how many doc links one component
 * may hold — many-to-many is legal — this only forbids the target of a
 * doc link from itself being a referencer of some OTHER target.
 * Self-documentation is explicitly not a chain in either direction
 * (#20's self-reference carve-out;
 * CrossComponentValidationTest.create_docComponentSelfReference_ok must
 * stay 2xx).
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

### 3. Concept/design doc: supersede, don't rewrite

In `docs/features/doc-support/doc-improvement-concept.md`, at rule #6
(`:105-106`, "version ranges with doc sections must not overlap") and
TODO #4 (`:308-310`, "single doc component per version"): add a dated
superseding note stating that many-to-many doc-component links are now
supported and a component may hold N simultaneous doc links, retained
there for history. Do not touch rule #7 (chain ban) — it's unaffected
and is exactly what change 2 implements; say so explicitly in the PR so
a reviewer doesn't assume the whole rules block changed.

### 4. Requirements traceability

Add to `docs/registry/requirements-common.md` (current tail is `SYS-065`
— re-verify immediately before landing, this is a race with any other
in-flight branch):
- **SYS-066**: a component may hold multiple simultaneous doc-component
  links (uncapped; deterministic single-value projection for the legacy
  surface via change 1). Codifies existing-but-unspecified behavior as
  an intentional, tested contract.
- **SYS-067**: doc→doc chain ban (change 2), including the self-doc
  carve-out as an explicit acceptance criterion.

Follow the existing template (Priority / Test layer / Status /
Motivation / Description / Acceptance criteria / Test method), mirroring
`SYS-064`/`SYS-065` (`requirements-common.md:2371-2472`).

## Tests

- `CrossComponentValidationTest`:
  - New positive test: create with two different doc components at the
    same version bucket (or both with no version) → 2xx, both present in
    the response. This is a characterization test locking in existing
    behavior as an intentional contract, not a test of new code.
  - New positive test: N (>2) doc links across different version buckets
    → 2xx.
  - New 400 test: chain-ban direction (a) — referencing a component that
    itself has doc links to a different component.
  - New 400 test: chain-ban direction (b) — giving doc links to a
    component already referenced as someone else's doc target.
  - Regression guard (load-bearing): `create_docComponentSelfReference_ok`
    (line 661) must stay 2xx unchanged; add a PATCH-side equivalent — a
    component whose doc links contain only its own key must stay 2xx on
    update too, in the same change as change 2.
- `ComponentDetailMapperTest.docs_sortedBySortOrder` (line 444): no code
  change needed — it already constructs two different doc keys sharing
  no version. Re-frame its display name/comment from "tolerate an
  ambiguous legacy shape" to "prove the now-canonical multi-doc-same-
  version shape sorts deterministically."
- `DatabaseComponentRegistryResolverTest`: add a case alongside existing
  `(6)/(6b)/(6c)` proving `sortOrder` breaks ties **between two different
  doc keys** sharing the same version — the existing cases only use one
  key at different versions, so they don't exercise the multi-key
  tie-break at all.

## Verification

1. `./gradlew :components-registry-service-server:test` — new/updated
   tests above, plus existing `(6)/(6b)/(6c)` and `#20` suites stay
   green.
2. `scripts/local-stands/verify.sh` per `docs/registry/compat-gate.md` —
   change 1 touches the legacy read surface (`AGENTS.md:160-164` mandates
   this for any read-path change).
3. Manual API spot check: create a component with 3 doc links (two
   sharing a version, one on a different one) → 201, all three present;
   confirm the legacy single-value read returns one deterministic
   value; attempt a chained doc target → 400.
4. Re-check `requirements-common.md` tail for the true next free
   requirement id immediately before landing.
