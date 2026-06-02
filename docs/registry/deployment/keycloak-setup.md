# Keycloak setup for components-registry-service

Operator-facing checklist for provisioning the Keycloak side of CRS authorization
in a fresh environment. This file documents **manual Admin-Console actions**
that aren't covered by the application source patches: the source code
(`application.yml`, `@PreAuthorize` annotations) only declares the role names
and permission map; an admin must create the matching realm-roles and decide
how users acquire them.

Audience: anyone deploying CRS into their own organisation's Keycloak realm.
Replace placeholders (`<your-realm>`, `<test-user>`) with the names that
apply in your environment.

---

## Prerequisites

1. A Keycloak realm exists where your users authenticate (e.g. via LDAP federation,
   SAML, or local accounts). Throughout this doc this is `<your-realm>`.
2. CRS is deployed and pointed at that realm (`auth-server.url` /
   `auth-server.realm` in `application.yml` or your config server).
3. You have `realm-admin` rights in `<your-realm>` to create roles and
   modify the default-roles composite.

## Role model implemented in CRS

CRS reads a role-→-permission map from `octopus-security.roles` (in
`application.yml` or merged via Spring Cloud Config). The keys defined
out of the box:

| Role key in CRS config | Permissions | Realm-role you must create in Keycloak |
|---|---|---|
| `ROLE_COMPONENTS_REGISTRY_VIEWER` | `ACCESS_COMPONENTS`, `ACCESS_AUDIT` | `COMPONENTS_REGISTRY_VIEWER` |
| `ROLE_COMPONENTS_REGISTRY_EDITOR` | `+ EDIT_COMPONENTS` | `COMPONENTS_REGISTRY_EDITOR` |
| `ROLE_ADMIN` | all permissions including `ARCHIVE_COMPONENTS`, `RENAME_COMPONENTS`, `DELETE_COMPONENTS`, `IMPORT_DATA`, `ADMIN_DATA` | usually a platform-wide `ADMIN` realm-role you already have |
| `ROLE_ANONYMOUS` | `ACCESS_COMPONENTS` (public read on v4 GETs) | implicit Spring authority — no Keycloak action needed |

Authority naming convention: `UserInfoGrantedAuthoritiesConverter` (from
`octopus-cloud-commons`) prefixes Keycloak role names with `ROLE_` when
building Spring authorities. So a Keycloak realm-role named
`COMPONENTS_REGISTRY_EDITOR` becomes the `ROLE_COMPONENTS_REGISTRY_EDITOR`
authority, which matches the YAML key. **Don't add the `ROLE_` prefix to
the Keycloak role name itself** — you'll get `ROLE_ROLE_*` and the map
will silently miss.

## Step 1 — create the two CRS realm-roles

Keycloak Admin Console:

1. Realms → select `<your-realm>` → **Roles** → **Realm Roles** tab → **Create role**:
   - **Role name:** `COMPONENTS_REGISTRY_EDITOR`
   - **Description:** Read + write components in components-registry-service
   - Composite: leave unchecked
2. Repeat for:
   - **Role name:** `COMPONENTS_REGISTRY_VIEWER`
   - **Description:** Read components + audit in components-registry-service
   - Composite: leave unchecked

Or via `kcadm.sh`:
```bash
kcadm.sh create roles -r <your-realm> \
  -s name=COMPONENTS_REGISTRY_EDITOR \
  -s 'description=Read + write components in components-registry-service'
kcadm.sh create roles -r <your-realm> \
  -s name=COMPONENTS_REGISTRY_VIEWER \
  -s 'description=Read components + audit in components-registry-service'
```

## Step 2 — decide who gets the editor role by default

Two common patterns; pick whichever matches your access policy.

### Option A — every authenticated user is an editor

Add `COMPONENTS_REGISTRY_EDITOR` to the realm's `default-roles-<your-realm>`
composite. Every existing and future user inherits the editor permissions
automatically.

Admin Console:
- Roles → **Default Roles** tab → in **Available Roles** select
  `COMPONENTS_REGISTRY_EDITOR` → **Add selected »**.

This realises the Phase-1 intent of [ADR-004](../adr/004-auth-keycloak.md):
"any authenticated user can read" plus a write step.

### Option B — explicit assignment per user / group

Skip the default-roles step. Assign `COMPONENTS_REGISTRY_EDITOR` (and/or
`COMPONENTS_REGISTRY_VIEWER`) only to users or groups that should have
the access. Use this if you need stricter access than "everyone in the
realm can write".

Admin Console:
- Users → select user → **Role Mappings** → assign role.
- Or: Groups → select group → **Role Mappings** → assign role; users in
  that group inherit it.

Either way, `COMPONENTS_REGISTRY_VIEWER` is intentionally kept out of
the default-roles composite — it's reserved for read-only consumers
(service accounts, auditors) that should not have write access even
when the rest of the org does.

## Step 3 — validation

In Keycloak Admin Console: pick a non-admin test user (`<test-user>`).
Use the **Impersonate** button on the user's profile (open it in an
incognito window or a separate browser profile so you don't lose your
admin session).

In the impersonated session, open the portal and check:

1. `GET /auth/me` returns the new role with the expected permissions:
   ```json
   {
     "username": "<test-user>",
     "roles": [
       {
         "name": "ROLE_COMPONENTS_REGISTRY_EDITOR",
         "permissions": ["ACCESS_COMPONENTS", "EDIT_COMPONENTS", "ACCESS_AUDIT"]
       },
       /* plus any other realm-roles the user already had */
     ]
   }
   ```
2. The components list page loads (no 403 banner).
3. The "New Component" button is visible (because `EDIT_COMPONENTS` is
   present).
4. Archive / Admin / Audit-restricted actions match the expected
   permission set for the role you assigned.

## Operational caveats

- **Token cache.** After you change `default-roles-<your-realm>` (or
  any user's role mapping), already-issued JWTs do not pick up the
  change. Users must re-login; for service accounts, the next
  `client_credentials` token request will be fresh. If your realm has
  a long `accessTokenLifespan` / `ssoSessionMaxLifespan`, plan a
  re-login window or invalidate sessions explicitly (Sessions → Logout
  all).
- **Naming.** `COMPONENTS_REGISTRY_EDITOR` (no `ROLE_` prefix) in
  Keycloak; `ROLE_COMPONENTS_REGISTRY_EDITOR` (with prefix) in
  `application.yml`. The converter adds the prefix.
- **`ROLE_ADMIN` mapping.** Out of the box, `ROLE_ADMIN` in
  `application.yml` grants the full CRS permission set, which assumes
  whatever realm-role your platform calls "admin" maps to it. If your
  platform admin role is not literally named `ADMIN`, adjust the
  `octopus-security.roles` map in your config server overlay
  accordingly (see [ADR-004](../adr/004-auth-keycloak.md) for the
  pattern).
- **Config-overlay lists REPLACE, they do not append.** Production reads
  `octopus-security.roles` from the `service-config` overlay
  (`<env>/service-config: components-registry-service.yml`), which Spring
  merges over the bundled `application.yml`. YAML *list* properties are
  replaced wholesale, so an overlay that redefines `ROLE_ADMIN` shadows the
  bundled permission list entirely. **When a new permission is added (e.g.
  `ADMIN_DATA`), it must be added to `ROLE_ADMIN` in every overlay too** —
  otherwise admins keep `ROLE_ADMIN` but silently lose the new capability
  (here: the Portal Field-Overrides edit surface). Verify post-deploy via an
  admin's `/auth/me` or the startup role-→-permission log.
- **Diagnosing a 403 when you expect 200.** Enable
  `logging.level.org.octopusden.cloud.commons.security: TRACE` in
  the CRS config. The startup log prints the resolved role-→-permission
  map; per-request logs show which authorities the JWT carries and
  what permission was checked.
