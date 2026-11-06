# ADR-024: `clientCode` validated against a customer directory (`octopus-customer-service`)

## Status
Proposed. Depends on decisions owned by the platform team that runs the upstream service
(see [Open questions](#open-questions)).

## Context

`clientCode` is free text today. The only list the Portal can show is
`GET /rest/api/4/components/meta/client-codes`, which is the set of codes *already used* by
components (SYS-046), not the set of customers that exist. A typo creates a new "customer"
silently, and ADR-020 keys client-specific components by that value.

Two internal services, both run by the platform team, expose customer data:

| | Customer read service | Onboarding service |
|---|---|---|
| Purpose | Read-only customer data API | Repository provisioning, with a customer list on the side |
| Customer data | `GET /api/clients?page&pageSize&orderBy&asc`, `GET /api/clients/{code}`; `code`, `name`, `type`, `status`, hosting, PGP, licenses | `GET /api/v1/clients`; `code`, `is_active`, …; unpaged; drops codes starting with `_` |
| Backing store | The platform's customer cache, read with a `client_credentials` token | A legacy database API, read with basic credentials |
| Auth | Keycloak resource server; accepts **service-account tokens only**, checked against an `allowed-callers` list | None |
| Stack | Kotlin / Spring Boot | Python / Flask |

Both lists ultimately derive from the ERP system of record, but only the read service
fronts the customer cache. Three writers fill that cache: a nightly sync from the ERP, an
SVN key scanner (PGP keys), and the customer-management UI (hosting, PGP waivers). The sync
is nightly because the ERP API is too slow to call per request. As a result, **a customer
created in the ERP can be missing from the directory for up to a day.**

The read service's current internal name, `*-client-service`, is ambiguous in every
consumer's code: "client" already means an OAuth client (`client-id`,
`client_credentials`) and an HTTP client (`EmployeeServiceClient`, `RestClient`).

## Decision

### 1. Customer data comes from the read service (proposed name `octopus-customer-service`)

Use the dedicated read service, not the onboarding service's `/clients`. It is the
service built for this, it authenticates callers, and it returns `status`, which validation
needs. Not going straight to the cache or its database keeps the dependency on the service
that owns the contract. The cache could later move behind it without consumers noticing.

A separate cross-team ADR, owned by the service's team, proposes renaming it
**`octopus-customer-service`** (the current name collides with "OAuth client" and "HTTP
client" in every consumer), moving it to the octopusden GitHub org after the
`octopus-employee-service` precedent, and making it the owner of the customer data. This
ADR uses the proposed name. If the rename is not adopted, the CRS property prefix, the
class names and the upstream path (`/api/clients` instead of `/api/customers`) change;
nothing else in this ADR does. The same ADR makes `/api/customers` and `/api/customers/{code}` the
service's canonical paths, with `/api/clients` kept as a deprecated alias; CRS calls
`/api/customers` only.

"Directory" stays at the consumer level: the CRS class is `CustomerDirectoryService`,
mirroring `EmployeeDirectoryService`.

**Terminology:** customer = the entity, `clientCode` = its identifier. The CRS v4 field
stays `clientCode`; it is part of the published contract and of the ADR-020 key format.

### 2. CRS validates `clientCode` on the v4 write path, mirroring ADR-015

The check belongs in CRS, not the Portal, so that every v4 API client (Portal, CLI,
scripts) gets it, not only the Portal form. **Scope is the same as ADR-015:** v4
`POST /rest/api/4/components` and `PATCH /rest/api/4/components/{id}`, plus template creates
(`POST /component-templates/{id}/components`), which go through the same create path. DSL import
(`ImportServiceImpl`) is unchanged: it migrates existing data, which is grandfathered.

- **Two-gate optional bean.** `customer-service.enabled=true` **and** a non-blank
  `customer-service.url` register the client; otherwise `CustomerDirectoryService` sees no
  bean (`ObjectProvider`) and the check is `DISABLED`.
- **Auth.** `client_credentials` with a dedicated CRS Keycloak client, whose
  `service-account-*` identity is added to the service's `allowed-callers`.
- **Cache.** The full customer list is held in memory as a snapshot with a TTL (~10 min,
  configurable). The upstream only pages, so a refresh reads all pages, and it is
  **all-or-nothing**: a failed or partial refresh keeps the last good snapshot. With no
  snapshot at all (first call, upstream down), the outcome is `UNAVAILABLE`, never
  `UNKNOWN`. There are a few hundred customers at most. The TTL adds nothing meaningful on
  top of the daily sync.
- **Statuses are configuration.** `customer-service.active-statuses` and
  `customer-service.inactive-statuses` list the `status` values that count as active and
  inactive. A value in neither list is "not recognised". Upstream stores `status` as a free
  string set by the sync job, so neither set is hard-coded (open question 2).
- **Fail-open semantics** (same split as ADR-015):

  | Outcome | Status | Effect on the write |
  |---|---|---|
  | code found, `status` in the active set | `ACTIVE` | allow |
  | code found, `status` in the inactive set | `INACTIVE` | **reject (400)** |
  | code found, `status` in neither set | `UNAVAILABLE` | allow + WARN log |
  | code absent from a good snapshot | `UNKNOWN` | **reject (400)**; admins may override, see below |
  | `clientCode` absent, empty or whitespace | none | allow; no lookup |
  | no snapshot / refresh failing | `UNAVAILABLE` | allow + WARN log |
  | no bean (flag off / blank URL) | `DISABLED` | allow |

  "Reject (400)" is the direct-write contract (v4 POST and PATCH). Template creates report
  the same outcomes in their own shape; see **Error contract**.
- **Explicit admin override for `UNKNOWN`.** Because of the daily sync, a legitimate
  customer created today is `UNKNOWN` until the next sync. The override needs **both** the
  `EDIT_ANY_COMPONENT` permission **and** an explicit request flag: a nullable body field
  `acknowledgeUnknownClientCode: Boolean? = null` on `ComponentCreateRequest`,
  `ComponentUpdateRequest` and `TemplateComponentRequest` (an additive v4 change per
  ADR-006, documented in OpenAPI). Without the flag, admins get the same 400 as
  everyone else, so a typo by an admin is still caught; the Portal offers the flag as a
  confirmation checkbox only in that error state, and only to admins. An overridden write
  is logged at WARN, and its audit-log row carries the marker `[clientCode override]` as
  the prefix of `change_comment` (`[clientCode override] <user comment>`), so overrides are
  queryable. The prefix is reserved like the ADR-023 sync prefix: `requireNotReserved`
  rejects it in a user-supplied `changeComment`, so the marker can't be forged. That guard
  must cover **every** path that takes a user-supplied `changeComment`, template creates
  included: `ComponentTemplateControllerV4` does not call `requireNotReserved` today, so
  adding it there is part of this change. Without the permission, the flag is ignored.
- **No code, no lookup.** `clientCode` is optional. An absent, empty or whitespace-only
  value means "no customer", as in the existing validator, and is never looked up, so a
  PATCH that clears the code with `""` is not rejected as `UNKNOWN`.
- **Timing.** The check runs on create, and on update only when `clientCode` changed, so
  legacy values that are no longer in the directory are grandfathered on unrelated edits.
  As in ADR-015, it is skipped for archived components and for a field whose field-config
  `visibility` is `hidden`.
- **Error contract.** The message always has the colon form `clientCode: <message>`, which
  the Portal's `parseServerFieldErrors` maps inline. The `UNKNOWN` message says the
  directory is synced daily, so a brand-new customer is not reported as simply "invalid".
  The Portal must tell `UNKNOWN` (offer the override) from `INACTIVE` (no override), so each
  rejection also carries a machine-readable code, `CLIENT_CODE_UNKNOWN` or
  `CLIENT_CODE_INACTIVE`:
  - **Direct creates and updates:** a 400 whose `ErrorResponse.errorCode` is that code (the
    existing discriminator field; clients already tolerate unknown values).
  - **How the code travels:** a plain `require(...)` is not enough. Its
    `IllegalArgumentException` becomes an `ErrorResponse` without `errorCode`, and
    `TemplateDryRun` keeps only its message. The rejection is therefore a dedicated exception
    that carries the code: `ControllerExceptionHandler` maps it to a 400 with `errorCode`, and
    `TemplateDryRun` copies the code into the template problem. The two constants are added
    to `ErrorCodes`.
  - **Template creates:** a problem in `TemplateRunResponse.problems`, with
    `fields: ["clientCode"]`, the same message, and a new nullable `code` field on the
    problem carrying the code (additive, per ADR-006). As for every other template problem,
    a dry run answers 200 with the problem listed, and a committed create answers 422 with
    the same body and creates nothing. The Portal reads the code from either shape and, for
    `CLIENT_CODE_UNKNOWN`, offers the admin checkbox, which sets
    `acknowledgeUnknownClientCode` on the retried request.
- **Dictionary endpoint.** New `GET /rest/api/4/components/meta/customers` →
  `[{ code, name, status }]`, served from the same snapshot, for the Portal picker. It
  requires **authentication plus `ACCESS_COMPONENTS`**, like the employee lookups in
  ADR-015, not `ACCESS_COMPONENTS` alone: the anonymous role carries that permission, and
  customer names and statuses must not be public. It includes `_`-prefixed
  codes only if open question 3 says they are valid. `/meta/client-codes` stays as is: it
  means "codes in use" and feeds the list filter. The two lists have different meanings
  and are not merged.

## Consequences

### Positive
- `clientCode` is checked against real customers on every v4 write, not only in the
  Portal form.
- One pattern for external-directory validation in CRS (employees, customers): same bean
  gating, same fail-open table, same error contract.
- A directory outage never blocks editing.

### Negative
- A new runtime dependency and a new Keycloak client for CRS, plus an allow-list entry on
  the platform side.
- Up to a day of staleness for newly created customers. Editors hold
  `CREATE_COMPONENTS` but not `EDIT_ANY_COMPONENT`, so they cannot finish such a create
  themselves: they wait for the sync, or an admin creates the component with the override
  (and ownership is set accordingly). An on-demand refresh on the platform side (open
  question 5) would remove this.
- DSL import still accepts any `clientCode`, as it accepts any person field today.
- CRS depends on the upstream's identity: if the service is renamed after CRS is wired in,
  the CRS Keycloak client stays, but the allow-list entry and URL move with it.

### Interaction with other ADRs
- **ADR-015** (employee-service validation): §2 is the same design applied to customers,
  plus a snapshot cache and an admin override that the per-request employee lookup does
  not need.
- **ADR-020** (component-key format): the key prefix is the lowercased `clientCode`. This
  ADR validates the code itself; it does not change the key rule. A `clientCode` may start
  with `_`. The onboarding service hides such codes as internal, so the read service's
  handling of them has to be confirmed (open question 3).

## Open questions
1. Is the read service deployed in every environment, and which deployed version is the
   contract?
2. The exact `status` values, and which of them count as active for a new component
   (seeds `customer-service.active-statuses` / `inactive-statuses`).
3. Does the read service return internal `_`-prefixed codes, and should they validate?
4. Timing of the rename (separate ADR) relative to CRS wiring.
5. Can a directory refresh be triggered on demand, for customers created today?

## References
- [ADR-015](015-employee-service-runtime-validation.md), [ADR-020](020-component-key-format.md)
- `GET /rest/api/4/components/meta/client-codes` (SYS-046): the existing "codes in use" list
