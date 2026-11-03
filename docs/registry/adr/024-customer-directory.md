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

### 1. Customer data comes from the read service, renamed `octopus-customer-service`

Use the dedicated read service, not the onboarding service's `/clients`. It is the
service built for this, it authenticates callers, and it returns `status`, which validation
needs. Not going straight to the cache or its database keeps the dependency on the service
that owns the contract. The cache could later move behind it without consumers noticing.

We propose renaming it **`octopus-customer-service`** and moving it to the octopusden
GitHub org, following the precedent of `octopus-employee-service`, a public octopusden
service in front of internal people data that CRS already uses (ADR-015).

**Name rationale:**

| Candidate | Verdict |
|---|---|
| `*-client-service` (current) | "client" collides with OAuth and HTTP clients. `ClientServiceClient` is unreadable |
| `octopus-client-service` | Still ambiguous |
| `octopus-customer-directory` | The backend services it sits next to all use the `-service` suffix (`employee-service`, `dms-service`, `release-management-service`, `components-registry-service`, …). Without it the name reads like a library or data repo |
| `octopus-customer-directory-service` | "directory" describes the consumer's role (read-only lookup), not the domain. `employee-service` is the same kind of directory without saying so. It would also mislead once the service owns writes (hosting, waivers) |
| **`octopus-customer-service`** | **Chosen.** Unambiguous, follows the `octopus-<domain>-service` pattern of its peers, pairs with `octopus-employee-service`, still fits if writes are added |

"Directory" stays at the consumer level: the CRS class is `CustomerDirectoryService`,
mirroring `EmployeeDirectoryService`.

**Terminology:** customer = the entity, `clientCode` = its identifier. The CRS v4 field
stays `clientCode`; it is part of the published contract and of the ADR-020 key format.
The service README must state this mapping so the two names are not read as two entities.

**Move to GitHub.** The move does not affect runtime dependencies: they are per-environment
config (upstream URL, Keycloak issuer and client, `allowed-callers`, deployment hostname),
not repo location. CRS and the Portal already run this way. The move needs:
- packages and `groupId` moved to `org.octopusden.octopus.customer`;
- a public base image and GitHub Actions on the octopus-base workflows, with images on ghcr;
- the upstream kept configurable, with no system-of-record names in code;
- a **fresh git history**, not a mirror, because the old history predates the read/write split;
- the `server` / `client` / `ft` module layout of `octopus-employee-service`, so CRS
  consumes a published Kotlin client.

If the rename and the move happen, they should happen **before** CRS is wired in. The
service name is embedded in its Keycloak client id, and from there in the
`service-account-*` identities on every allow-list.

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
  | no snapshot / refresh failing | `UNAVAILABLE` | allow + WARN log |
  | no bean (flag off / blank URL) | `DISABLED` | allow |

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
  rejects it in a user-supplied `changeComment`, so the marker can't be forged. Without the
  permission, the flag is ignored.
- **Timing.** The check runs on create, and on update only when `clientCode` changed, so
  legacy values that are no longer in the directory are grandfathered on unrelated edits.
  As in ADR-015, it is skipped for archived components and for a field whose field-config
  `visibility` is `hidden`.
- **Error contract.** A 400 in the colon form `clientCode: <message>`, which the Portal's
  `parseServerFieldErrors` maps inline. The `UNKNOWN` message says the directory is
  synced daily, so a brand-new customer is not reported as simply "invalid".
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
- The rename and the move are the platform team's call. If they don't happen, CRS still
  integrates; only the property prefix and class names change.

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
4. Rename and move: accepted or not, and when, relative to CRS wiring.
5. Can a directory refresh be triggered on demand, for customers created today?

## References
- [ADR-015](015-employee-service-runtime-validation.md), [ADR-020](020-component-key-format.md)
- `octopus-employee-service`: naming and module-layout precedent
- `GET /rest/api/4/components/meta/client-codes` (SYS-046): the existing "codes in use" list
