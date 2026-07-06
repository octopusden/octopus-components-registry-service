# TD-015: Replace the service-event ingest shared-secret with an OIDC service account

## Status

Open. Shipped with the shared-secret scheme (SYS-061); upgrade tracked here.

## Background

`POST /rest/api/4/admin/service-events` (SYS-061) lets the portal BFF report its own
operational events (portal redeploys, validation-sweep runs) into the CRS
`service_event` journal. The portal calls CRS **tokenless** today (the validation sweep
hits CRS with no bearer token), and the portal events are emitted from **background
threads** (`ApplicationReadyEvent`, the sweep scheduler) that carry **no user JWT** to
relay — so the endpoint needs a *machine* identity, not the caller's user token.

The v1 solution is a **shared-secret header** (`X-Service-Event-Token`): the value lives
in Vault, is verified constant-time in `ServiceEventIngestControllerV4`, and is
**fail-closed** (blank/unset → 403). The POST is method-scoped `permitAll` at the filter
chain so the sibling GET read stays JWT + `IMPORT_DATA` gated.

This is acceptable for an internal, journal-only endpoint (no component data is mutated;
single-pod; internal network) but it is **not the platform-native scheme**: it is a
symmetric pre-shared key rather than a real identity, separate from the Keycloak/OIDC
mechanism that secures everything else on `/rest/api/4/**`.

## Target

Client-credentials **service account** via Keycloak:

- Portal BFF acquires its own machine token (client-credentials grant) and calls CRS with
  a normal `Bearer` JWT (client secret from Vault, like the other `application-cloud-*`
  secrets).
- CRS authorizes the ingest via a role/permission (mirror `AdminControllerV4`'s
  `@permissionEvaluator` gate) and **drops** the `permitAll` matcher + the shared-secret
  check.
- Remove `X-Service-Event-Token` and `components-registry.service-events.ingest-token` /
  `portal.service-events.token`.

## Effort / notes

- Provision (or reuse) a Keycloak client for the portal BFF; wire client-credentials token
  acquisition into the BFF's outbound CRS calls.
- Map the client's role to a CRS permission and remove the method-scoped `permitAll` in
  `WebSecurityConfig`.
- No data migration; the `service_event` table and read API are unaffected.
- Until then the shared-secret path is safe as a temporary measure (fail-closed, journal-only).
