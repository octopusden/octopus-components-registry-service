## Why

CRS models a component's Java/Maven build version in two manual tiers: **DEFAULT** (the base, `ALL_VERSIONS` value) and **OVERRIDDEN** (a per-range value). RMS separately records the version **ACTUALLY** used by each build (OCTOPUS-2256, done) — but CRS has no visibility into it. CRS's configured value can therefore silently contradict what RMS recorded for RC/RELEASE builds in that same range, with no reconciliation between the two systems today.

This matters most as a display problem: a manager or Portal user looking at a component wants to know the actual Java/Maven version RMS recorded, not just what CRS was told to expect. Without this change, that information isn't available anywhere in CRS's own API.

## What Changes

CRS becomes three-tier: DEFAULT and OVERRIDDEN stay manual and editable; RMS's registered value is exposed as a new, read-only **ACTUAL** tier, never merged into or replacing the configured values.

- **Display (Part A):** CRS's v4 API exposes ACTUAL as independent per-attribute (Java, Maven) lists of version ranges, sourced only from RMS builds with status `RC` or `RELEASE`. The components list view shows one rollup number per attribute (the maximum version seen across all ranges); the component detail view shows the full range list, with a warning flag on any DEFAULT/OVERRIDDEN row that disagrees with an intersecting ACTUAL range. Read is best-effort: if RMS is unreachable, ACTUAL reports itself unavailable rather than failing the request.
- **Block override (Part B):** *writing* a new DEFAULT or OVERRIDDEN value is rejected if the range being written intersects a non-null ACTUAL value for that same attribute. This is a rule about the act of writing, not about the resulting state — a mismatch that already exists in stored data (predating ACTUAL data, or exposed by deleting an override) is never retroactively enforced against; it only ever shows as the Part A warning. If the live RMS check is ambiguous or fails at write time, the write is rejected (fail-closed).

### A note on the CRS↔RMS coupling

RMS's `server` module already depends on CRS's client library (`components-registry-service-client`). Adding a naive reverse dependency (CRS depending on RMS's published `client` artifact) would not actually be circular — RMS's `client` module has no dependency on CRS — but it would add a new cross-repo `gradle.properties` version pin between the two services. This change deliberately avoids that: CRS gets its own thin, hand-rolled HTTP client against RMS's build-listing endpoint, mirroring a working precedent already in this ecosystem (`octopus-components-management-portal`'s `ReleaseManagementClient.kt`). No new build-time dependency is introduced in either direction.

## Affected areas

- `components-registry-service-server` only — no other CRS module needs changes.
- CRS's v4 API response shapes: `ComponentSummaryResponse` (new rollup fields) and `ComponentDetailResponse` (new per-attribute range lists + warning flags).
- CRS's v4 write endpoints (base config `PATCH`, field-override create/update, bulk field-overrides apply-plan) gain a new validation gate.

## Out of scope

- **Legacy v1–v3 API.** Confirmed read-only (no write endpoints exist outside `ComponentControllerV4`) and unrelated to java/maven overrides — no gate is needed, and they will not carry the new ACTUAL data. v2's existing, unmodified response (reading the configured value from CRS's own DB) continues to serve as a fallback for any consumer that doesn't need ACTUAL; nothing changes there.
- **`deleteFieldOverride`.** Deletion never writes a new conflicting value — it only ever reveals existing state (which may or may not already disagree with ACTUAL), and that state is exactly what Part A's warning display exists to surface. No gate is added to the delete endpoint.
- **The Git/DSL import path** (`ImportServiceImpl`), which also writes `javaVersion`/`mavenVersion`. It's a one-time migration mechanism used when migrating a component into CRS, not a standing user-facing edit path — the gate does not apply there.
- **Openspec tooling scaffolding** (`.claude/commands/opsx/*`, `.claude/skills/openspec-*`, `openspec/config.yaml`). This change ships only the four content files for this specific change; adopting the broader tooling is a separate decision.
- CRS is not intended to become the source of truth for RMS's registered value — RMS is. This change exposes it for the editing workflow's display convenience, not as a general-purpose relay other services should depend on instead of querying RMS directly.
