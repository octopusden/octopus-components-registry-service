# TD-001: Embedded UI V1 Hardening

## Status

Open

## Context

The current V1 implementation ships the React UI and backend API as a single deployable unit:

- UI is built in `components-registry-ui`
- server packaging copies `dist/` into the Spring Boot artifact
- Spring MVC serves the SPA from classpath static resources

This is acceptable for V1 and matches the current operational simplicity goals, but a few technical
issues should be corrected now to keep the implementation maintainable and avoid accidental
coupling.

## Problem Statement

The embedded UI approach currently has three weak points:

1. SPA fallback routing is broader than necessary and may intercept non-UI server endpoints.
2. UI-to-server packaging has more than one apparent build path, which creates ambiguity.
3. UI and API path conventions should be made explicit and stable so the code does not become
   embedded-only by accident.

## Scope

### 1. Tighten SPA routing boundary

Review and harden SPA fallback behavior in:

- `components-registry-service-server/src/main/kotlin/org/octopusden/octopus/components/registry/server/config/SpaWebConfig.kt`

Goal:

- SPA fallback must not interfere with non-UI endpoints
- server endpoints such as API, actuator, docs, and error paths must keep their intended behavior

Possible acceptable solutions:

- serve SPA only under a dedicated UI path such as `/ui/**`
- or keep the current broad handler but explicitly exclude all non-UI paths that must never fall
  back to `index.html`

### 2. Make UI packaging path canonical

Review and simplify the packaging flow between:

- `components-registry-ui/package.json`
- `components-registry-service-server/build.gradle`

Goal:

- there must be one clear canonical way to package UI assets into the server artifact
- any auxiliary build command must be clearly secondary and non-conflicting

### 3. Stabilize UI base path expectations

Clarify and enforce:

- the intended UI base path
- the intended API base path
- the boundary between dev proxying and production packaging

Goal:

- frontend code should not assume an embedded-only runtime model in places where a later same-repo,
  separate-deployable evolution might be desired

## Out of Scope

- switching to a separate UI deployable
- changing OKD deployment topology
- adding gateway or Keycloak integration
- changing ADRs or wider architecture docs as part of this task

## Acceptance Criteria

- SPA fallback no longer risks breaking non-UI server endpoints
- one canonical build/package path for embedding UI into the server artifact is documented in code
  and reflected in the build
- UI path and API path behavior are explicit and consistent
- local dev flow and production packaging do not contradict each other

## Suggested Validation

- open the UI root path and verify SPA loads correctly
- call representative non-UI endpoints and verify they are not swallowed by SPA fallback:
  - `/rest/api/...`
  - `/actuator/health`
  - `/swagger-ui/index.html`
  - `/v3/api-docs`
- run the canonical build path and verify the boot JAR contains the current UI assets

## Likely Touched Files

- `components-registry-service-server/src/main/kotlin/org/octopusden/octopus/components/registry/server/config/SpaWebConfig.kt`
- `components-registry-service-server/build.gradle`
- `components-registry-ui/package.json`
- optionally `components-registry-ui/vite.config.ts`
