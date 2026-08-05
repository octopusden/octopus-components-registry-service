## Why

CRS models a component's Java/Maven build version in two manual tiers: **DEFAULT** (the base, `ALL_VERSIONS` value) and **OVERRIDDEN** (a per-range value). RMS separately records the version **ACTUALLY** used by each build (OCTOPUS-2256, done) — but CRS has no visibility into it. CRS's configured value can therefore silently contradict what RMS recorded for RC/RELEASE builds in that same range, with no reconciliation between the two systems today.

This matters most as a display problem: a manager or Portal user looking at a component wants to know the actual Java/Maven version RMS recorded, not just what CRS was told to expect. Without this change, that information isn't available anywhere in CRS's own API.

## What Changes

CRS becomes three-tier: DEFAULT and OVERRIDDEN stay manual and editable; RMS's registered value is exposed as a new, read-only **ACTUAL** tier, never merged into or replacing the configured values.

- **Display (Part A):** CRS's v4 API exposes ACTUAL as independent, minor-version-aware, per-attribute (Java, Maven) lists of version ranges, sourced only from RMS builds with status `RC` or `RELEASE`. The components list view shows one rollup number per attribute (the maximum version seen across all ranges — a known, accepted limitation: this can reflect a numerically-higher but superseded line, not necessarily "what the component currently builds on"); the component detail view shows the full range list, with a warning naming any DEFAULT/OVERRIDDEN sub-range that disagrees with ACTUAL. Read is best-effort: if RMS is unreachable, ACTUAL reports itself unavailable rather than failing the request.
- **Block override (Part B):** writing a DEFAULT or OVERRIDDEN value is rejected only when the write would actually change the effective value/range **and** the resulting value **disagrees** with a non-null, intersecting ACTUAL value. Setting a value to match what RMS actually recorded is always permitted — that's how a warning gets resolved, not by deleting the override. If the live RMS check is ambiguous or fails at write time, the write is rejected (fail-closed).

This is the result of two rounds of adversarial review. The first draft blocked any write whose range intersected non-null ACTUAL data, regardless of whether the new value agreed with it — which made a warning uncorrectable except by deletion (and DEFAULT can't be deleted at all, so a DEFAULT mismatch would have had no remedy). The rule now keys on disagreement, not mere presence of ACTUAL data.

### A note on the CRS↔RMS coupling

RMS's `server` module already depends on CRS's client library (`components-registry-service-client`). Adding a naive reverse dependency (CRS depending on RMS's published `client` artifact) would not actually be circular — RMS's `client` module has no dependency on CRS — but it would add a new cross-repo `gradle.properties` version pin between the two services. This change deliberately avoids that: CRS gets its own thin, hand-rolled HTTP client against RMS's build-listing endpoint, mirroring a working precedent already in this ecosystem (`octopus-components-management-portal`'s `ReleaseManagementClient.kt`). No new build-time dependency is introduced in either direction.

## Affected areas

- `components-registry-service-server` only — no other CRS module needs changes.
- CRS's v4 API response shapes: `ComponentSummaryResponse` (new rollup fields) and `ComponentDetailResponse` (new per-attribute range lists + warnings).
- CRS's v4 write endpoints (`createComponent`, base config `PATCH`, field-override create/update, bulk field-overrides apply-plan) gain a new validation gate.

## Out of scope

- **Legacy v1–v3 API.** Confirmed read-only (no write endpoints exist outside `ComponentControllerV4`) and unrelated to java/maven overrides — no gate is needed, and they will not carry ACTUAL. v2's existing, unmodified response continues to serve as a fallback for any consumer that only needs the configured value.
- **`deleteFieldOverride`.** Deletion never writes a new conflicting value — it only reveals existing state, which the warning display exists to surface. No gate is added to the delete endpoint.
- **The Git/DSL import path** (`ImportServiceImpl`), which also writes `javaVersion`/`mavenVersion`. It's a one-time migration mechanism, not a standing user-facing edit path — the gate does not apply there.
- **Openspec tooling scaffolding.** This change ships only the four content files for this specific change.
- CRS is not intended to become the source of truth for RMS's registered value — RMS is. This change exposes it for the editing workflow's display convenience, not as a general-purpose relay other services should depend on instead of querying RMS directly.

## Rollout note

RMS integration configuration (base URL) must land in, or before, the same deploy as this feature in every environment. Once deployed, a disabled or unconfigured RMS integration fails closed on every `javaVersion`/`mavenVersion` write (same as RMS being unreachable) — an environment that forgets to configure it will find every such write rejected, not silently unenforced.
