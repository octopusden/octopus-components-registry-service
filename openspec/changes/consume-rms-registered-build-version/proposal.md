## Why

CRS models a component's Java/Maven build version in two manual tiers: 
- **DEFAULT** (the base, `ALL_VERSIONS` value).
- **OVERRIDDEN** (a per-range value). 

RMS separately records the version **ACTUALLY** used by each build — but CRS has no visibility into it. CRS's configured value can therefore silently contradict what RMS recorded for RC/RELEASE builds in that same range, with no reconciliation between the two systems today.

This matters most as a display problem: a manager or Portal user looking at a component wants to know the actual Java/Maven version RMS recorded, not just what CRS was told to expect. Without this change, that information isn't available anywhere in CRS's own API.

## What Changes

CRS becomes three-tier: DEFAULT and OVERRIDDEN stay manual and editable; RMS's registered value is exposed as a new, read-only **ACTUAL** tier, never merged into or replacing the configured values. This applies only to components built with Maven or Gradle — a Java/Maven version is meaningless for any other build system, so those components are entirely untouched by this feature.

- **Display (Part A):** CRS's v4 API exposes ACTUAL as independent, per-attribute (Java, Maven) lists of version ranges built by walking a component's RC/RELEASE builds in real version order and collapsing consecutive same-valued builds — no version-format-dependent bucketing. The components list view shows one rollup number per attribute (the maximum version seen across all ranges — a known, accepted limitation: this can reflect a numerically-higher but superseded line, not necessarily "what the component currently builds on"; for Maven, the literal token `"LATEST"` always wins this comparison, since it isn't a comparable version number). The component detail view shows the full range list, with a warning naming any DEFAULT/OVERRIDDEN sub-range that disagrees with ACTUAL. Read is best-effort: if RMS is unreachable, each row is marked "ACTUAL data unavailable" rather than the request failing.
- **Block override (Part B):** writing a DEFAULT or OVERRIDDEN value is rejected only when the write would actually change the effective value/range **and** the resulting value **disagrees** with a non-null, intersecting ACTUAL value. Setting a value to match what RMS actually recorded is always permitted — that's how a warning gets resolved, not by deleting the override. This only applies while RMS integration is enabled: if it's disabled or unconfigured, this whole feature is off and nothing is ever blocked (see Rollout note). While enabled, if the live RMS check is ambiguous or fails at write time, the write is rejected (fail-closed) — but only for the specific field actually being changed; an outage never blocks an edit to anything else.

This design went through multiple rounds of adversarial review. The most significant fixes: an early draft blocked any write whose range intersected non-null ACTUAL data, regardless of whether the new value agreed with it — which made a warning uncorrectable except by deletion (and DEFAULT can't be deleted at all, so a DEFAULT mismatch would have had no remedy). The rule now keys on disagreement, not mere presence of ACTUAL data. A later fix separated "RMS integration disabled" (the feature is simply off) from "RMS unreachable" (the feature is on but its dependency failed) — the two used to be treated identically, which would have made this feature unsafe to deploy before RMS config existed in every environment. A further simplification dropped an earlier `major.minor`-bucketing step for building ACTUAL ranges — format-fragile and unnecessary — in favor of directly walking the real, sorted build sequence and collapsing consecutive same-valued builds, closing a run on any differing *or null* observation. A 404 from RMS during the display sweep is now explicitly treated the same as any other failure (unavailable, never read as "confirmed clean"), and a write rejected for an ACTUAL conflict now refreshes that one component's cached display data immediately, so the UI doesn't keep showing a clean row after a rejection references data it hasn't caught up to yet.

Two things were deliberately **not** changed despite real trade-offs raised in review, and are documented rather than fixed (see design.md's Risks): a null (unrecorded) build always breaks a run — even though this can fragment an unchanged value around old, untracked history, and can cancel forward-coverage protection if the single most recent build happens to lack data — because the alternative (assuming a missing build agrees with its neighbors) means inferring a value CRS was never told, which was judged worse; and a component's build system can be changed away from Maven/Gradle, edited freely while the gate doesn't apply, then changed back, storing a value that was never checked — accepted because changing a component's build system is expected to be very rare.

### A note on the CRS↔RMS coupling

RMS's `server` module already depends on CRS's client library (`components-registry-service-client`). Adding a naive reverse dependency (CRS depending on RMS's published `client` artifact) would not actually be circular — RMS's `client` module has no dependency on CRS — but it would add a new cross-repo `gradle.properties` version pin between the two services. This change deliberately avoids that: CRS gets its own thin, hand-rolled HTTP client against RMS's build-listing endpoint, mirroring a working precedent already in this ecosystem (`octopus-components-management-portal`'s `ReleaseManagementClient.kt`). No new build-time dependency is introduced in either direction.

## Affected areas

- `components-registry-service-server` only — no other CRS module needs changes.
- CRS's v4 API response shapes: `ComponentSummaryResponse` (new rollup fields) and `ComponentDetailResponse` (new per-attribute range lists + warnings).
- CRS's v4 write endpoints (base config `PATCH`, field-override create/update, bulk field-overrides apply-plan) gain a new validation gate. `createComponent` is not gated — a new component's key can never collide with prior RMS history, since components are archived, not hard-deleted (so a key is never freed up for reuse).

## Out of scope

- **Legacy v1–v3 API.** Confirmed read-only (no write endpoints exist outside `ComponentControllerV4`) and unrelated to java/maven overrides — no gate is needed, and they will not carry ACTUAL. v2's existing, unmodified response continues to serve as a fallback for any consumer that only needs the configured value.
- **`deleteFieldOverride`.** Deletion never writes a new conflicting value — it only reveals existing state, which the warning display exists to surface. No gate is added to the delete endpoint.
- **The Git/DSL import path** (`ImportServiceImpl`), which also writes `javaVersion`/`mavenVersion`. It's a one-time migration mechanism, not a standing user-facing edit path — the gate does not apply there.
- **Openspec tooling scaffolding.** This change ships only the four content files for this specific change.
- CRS is not intended to become the source of truth for RMS's registered value — RMS is. This change exposes it for the editing workflow's display convenience, not as a general-purpose relay other services should depend on instead of querying RMS directly.

## Rollout note

A disabled or unconfigured RMS integration means this whole feature is off — no ACTUAL data shown, no writes blocked, behaving exactly as if the feature didn't exist. This is safe to deploy to every environment immediately: nothing needs to be configured first, and no write is ever rejected because RMS integration hasn't been set up yet. The feature activates per-environment whenever its RMS config is added. This is a deliberate, safer choice than fail-closed-when-unconfigured — see design.md Decision 6 for why disabled and unreachable are treated differently.
