# Service-event ingest token (SYS-061)

How to provision the shared secret that lets the **portal** report its operational events
(portal redeploys, "Validation of components" sweep runs) into the CRS `service_event`
journal shown on the Admin → **Events** tab.

> Not needed for CRS-side events. Redeploys of CRS, data migrations, and TeamCity resync
> are recorded by CRS itself and appear on the Events tab **without any token**. The token
> only gates the portal → CRS ingest endpoint (`POST /rest/api/4/admin/service-events`).

## What the token is

A **symmetric shared secret** between the portal BFF and CRS — it is not issued by Keycloak
and has nothing to do with OIDC. The portal sends it as the `X-Service-Event-Token` header;
CRS compares it constant-time and is **fail-closed** (blank/unset or mismatch → `403`, so
portal events are simply not recorded). It is an interim scheme; the OIDC service-account
replacement is tracked in [tech-debt/015](../tech-debt/015-service-event-ingest-auth.md).

## One secret PER ENVIRONMENT

Use a **different** value for QA and prod (a QA leak must not affect prod), but the **same**
value on both services within one environment (it is a shared secret for the CRS↔portal pair).

| Environment | CRS key | Portal key | must match |
|-------------|---------|------------|------------|
| QA   | `components-registry.service-events.ingest-token` | `portal.service-events.token` | same value within QA |
| prod | `components-registry.service-events.ingest-token` | `portal.service-events.token` | same value within prod |

## Steps

1. **Generate** a secret per environment:
   ```
   openssl rand -hex 32
   ```
2. **Store in Vault** under each environment's Spring Cloud Config profile
   (`application-cloud-qa` / `application-cloud-prod`), by property key — same as every other
   secret (nothing is committed to `service-config`):
   - CRS profile: `components-registry.service-events.ingest-token = <secret>`
   - Portal profile: `portal.service-events.token = <same secret for that env>`
3. **Roll** both services (or wait for the next deploy) so they pick up the config.

The token is the single on/off switch on both sides — setting it (non-blank) turns reporting
on; there is no separate enable flag. Leaving it blank/unset means the portal records nothing
and CRS-side events still work.

## Verify

- Portal log has no `Failed to report ... service-event to CRS` warnings.
- After a portal redeploy, the Admin → Events tab shows a `STARTUP` row with `source=portal`.
- After a validation sweep completes, a `VALIDATION_SWEEP` row appears (`source=portal`).
- A `403` on the portal→CRS POST means the two values differ or one side is blank.

## Rollback / disable

Blank/remove the token (either side). Portal events stop being recorded immediately; CRS-side
events are unaffected. No schema or data change.
