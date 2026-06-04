# ADR-015: Employee-service runtime validation of person fields

## Status
Accepted

## Context

The old Groovy/Kotlin DSL model validated component person fields
(`componentOwner`, `releaseManager`, `securityChampion`) at **config-load / CI
time**:

- `EscrowConfigValidator` enforced the cheap rules — `componentOwner` required
  (non-blank) on every component; `releaseManager` / `securityChampion` required
  and matching a username pattern **only when** `distribution.explicit &&
  distribution.external`.
- A separate, default-off (`cr.employeeServiceEnabled=false`) build/CI task,
  `ComponentRegistryValidationTask`, called employee-service's
  `getEmployee(username)` and rejected any owner/RM/SC that was unknown or
  inactive (scoped to non-archived components).

The v3/v4 model writes components straight to PostgreSQL through the v4 REST API
— a write *is* publication, there is no separate CI gate. The audit
`.github/audit/VALIDATION-PARITY-2026-06-03.md` (rows #1, #3, #4, #7) confirmed
that **none** of the person-field validation carried over: any string was
accepted, and there was no employee-service usage anywhere in the server module.

The employee-service client (`org.octopusden.octopus.employee:client`) was only a
**build-time / runtime-transitive** dependency of `component-resolver-core`
(declared `implementation`, not `api`, under `java-library`), so the server
module could not even reference `EmployeeServiceClient` at compile time.

## Decision

Restore person-field validation on the v4 write path (`POST
/rest/api/4/components`, `PATCH /rest/api/4/components/{id}`) and **modernise**
the active-employee check from a default-off CI gate into a runtime check at the
API.

1. **Employee-service becomes a runtime dependency.** The server module declares
   `org.octopusden.octopus.employee:client` explicitly (it was previously only a
   runtime-transitive dep). A two-gate optional bean (`EmployeeServiceConfig` +
   `EmployeeServiceProperties`, prefix `employee-service.*`) builds a
   `ClassicEmployeeServiceClient` only when `employee-service.enabled=true`
   **and** `url` is non-blank — mirroring the inert-default `TeamcityProperties`
   / `FaultInjectionConfig` patterns. `EmployeeDirectoryService` wraps the
   optional bean via `ObjectProvider<EmployeeServiceClient>`, so the rest of the
   code works whether or not the bean exists.

2. **Fail-open semantics** distinguish "definitely bad" from "can't tell":

   | Outcome | `ActiveStatus` | Effect on the write |
   |---|---|---|
   | `active == true` | `ACTIVE` | allow |
   | `active == false` | `INACTIVE` | **reject (400)** |
   | `NotFoundException` | `UNKNOWN` | **reject (400)** |
   | transport/timeout/5xx | `UNAVAILABLE` | allow + WARN log |
   | no client bean (flag off / blank URL) | `DISABLED` | allow |

   Employee-service being **unreachable** must never become a hard outage
   dependency for editing components; only a definitive "user does not exist" or
   "user is inactive" rejects.

3. **Validation timing** (see `functional-spec.md` §1.3/§1.4):
   - **Required / pattern** run **unconditionally** (no external dependency):
     `componentOwner` non-blank on every component; `releaseManager` /
     `securityChampion` required + per-element `^\w+$` only under `explicit &&
     external`. Lists are validated **per canonical element** (an element like
     `"alice,bob"` fails the pattern — it is *not* CSV-split).
   - **Active-employee** runs only for non-archived components when a person field changed **or** the
     distribution gate flipped (so pre-existing saved values are
     **grandfathered** on unrelated edits), and only when the bean is present.
   - A field whose field-config `visibility` is `hidden` is **skipped** (a
     hidden field is stripped, so it cannot be required).

4. **Error contract.** All failures use `require(...)` →
   `IllegalArgumentException` → **400** via `ControllerExceptionHandler`, with a
   message that **starts with the exact field name** (`componentOwner …`,
   `releaseManager …`, `securityChampion …`) so the Portal's
   `parseServerFieldErrors` can map it inline.

5. **UI lookup endpoints** (Stage 2) back the picker + inactive badge:
   - `GET /rest/api/4/components/meta/employees?search=<q>` → `[{username,
     active}]` (exact probe — the client has **no** prefix search; typeahead
     suggestions still come from `/meta/owners`).
   - `POST /rest/api/4/components/meta/employees/status` body `[username…]` →
     `{username: active|null}` (`null` = unknown/unavailable/disabled → no
     badge). Both require authentication plus `ACCESS_COMPONENTS`, both fail-open.

## Consequences

### Positive
- Closes audit gaps #1/#3/#4/#7 — the highest-priority "explicit ask" gaps.
- Stronger than the old behavior (per-request, not a default-off CI gate) while
  preserving the old conditionality.
- Degrades safely: with the flag off (the default, incl. all current envs and
  tests) the required/pattern checks still run, and the active check is a no-op.

### Negative
- Employee-service is now on the runtime hot path for component writes (behind
  the flag + fail-open). An operator must set `employee-service.url` (+ token or
  basic creds) in `service-config` per env to turn the active check on.
- The picker is "suggest-from-owners + validate/annotate-on-exact", not a true
  directory search, because the client exposes only exact `getEmployee`.

### Interaction with other ADRs
- **ADR-011 (Field Configuration)**: hidden person fields are skipped by this
  validation (consistent with the hidden-field strip on write).
- **ADR-005 (Audit Log)**: no new audit surface; validation runs before the
  existing CREATE/UPDATE audit events.
- **ADR-004 (Keycloak Auth)**: the lookup endpoints reuse `ACCESS_COMPONENTS`,
  matching `/meta/owners`.

## References
- Audit: `.github/audit/VALIDATION-PARITY-2026-06-03.md` (rows #1/#3/#4/#7, §5
  caveats, §6 headline recommendation).
- Old reference: `ComponentRegistryValidationTask` (`getEmployeeServiceClient`,
  `findErrors`) and `EscrowConfigValidator` person-field rules on `main`.
- Ledger: `docs/registry/tech-debt/012-pre-publish-validation-parity.md`.
