## Why

CRS lets an editor configure a per-version-range `javaVersion`/`mavenVersion` for a component's build. RMS (release-management-service) already records the *actual* Java/Maven version used by each build (OCTOPUS-2256, done) — but CRS has no visibility into it. CRS's configured value can therefore silently contradict what RMS recorded for RC/RELEASE builds in that same range, with no reconciliation between the two systems.

This matters most as a display problem: a manager or Portal user looking at a component's build configuration wants to know the actual Java/Maven version RMS recorded, not just what CRS was told to expect. Without this change, that information simply isn't available anywhere in CRS's own API.

## What Changes

- **Display (Part A):** CRS's v4 API exposes RMS's registered Java/Maven version per component, as a list of version ranges — sourced only from RMS builds with status `RC` or `RELEASE`. Read is best-effort: if RMS is unreachable, the field reports itself unavailable rather than failing the request.
- **Block override (Part B):** editing a range's configured `javaVersion`/`mavenVersion` is only permitted where RMS's registered value for that specific range is null. If RMS is unreachable at write time, the edit is rejected (fail-closed) rather than silently allowed.

### A note on the CRS↔RMS coupling

RMS's `server` module already depends on CRS's client library (`components-registry-service-client`). Adding a naive reverse dependency (CRS depending on RMS's published `client` artifact) would not actually be circular — RMS's `client` module has no dependency on CRS — but it would add a new cross-repo `gradle.properties` version pin between the two services. This change deliberately avoids that: CRS gets its own thin, hand-rolled HTTP client against RMS's build-listing endpoint, mirroring a working precedent already in this ecosystem (`octopus-components-management-portal`'s `ReleaseManagementClient.kt`). No new build-time dependency is introduced in either direction.

## Affected areas

- `components-registry-service-server` only — no other CRS module needs changes.
- CRS's v4 API response shape (new field on the component detail response).
- CRS's v4 write endpoints (base config `PATCH`, field-override create/update, bulk field-overrides apply-plan) gain a new validation gate.

## Out of scope

- **Legacy v1–v3 API.** Confirmed read-only (no write endpoints exist outside `ComponentControllerV4`) and unrelated to java/maven overrides — no gate is needed, and they will not carry the new registered-value field. v2's existing, unmodified response (reading the *configured* value from CRS's own DB) continues to serve as a fallback for any consumer that doesn't need RMS's registered-value annotation; nothing changes there.
- **The Git/DSL import path** (`ImportServiceImpl`), which also writes `javaVersion`/`mavenVersion`. It's a one-time migration mechanism used when migrating a component into CRS, not a standing user-facing edit path — the RMS gate does not apply there.
- **Openspec tooling scaffolding** (`.claude/commands/opsx/*`, `.claude/skills/openspec-*`, `openspec/config.yaml`). This change ships only the four content files for this specific change; adopting the broader tooling is a separate decision.
- CRS is not intended to become the source of truth for RMS's registered value — RMS is. This change exposes it for the editing workflow's display convenience, not as a general-purpose relay other services should depend on instead of querying RMS directly.
