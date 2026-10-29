# ADR-019: User feedback storage, transport, and security

## Status
Accepted.

## Context

The portal (`octopus-components-management-portal`) had no channel for users to report
problems or send feedback, and no operator view of what users report. SYS-062 adds a
single feedback surface (one form, `type` = BUG / IDEA / QUESTION) with optional
screenshots, plus an admin-only triage view (list / filter / detail / status). This
ADR records the non-obvious decisions; the requirement is SYS-062 in
`requirements-common.md`.

There is no object store (S3/MinIO) in the CRS stack, screenshots are small, and the
portal's API wrapper transports JSON only. Feedback is user-submitted free-form content
with its own lifecycle — unlike `audit_log` (entity changes) or `service_event` (job
runs), so it gets its own tables, not a reuse of either.

## Decision

1. **Storage: `bytea` in the existing Postgres.** A `feedback` row plus one
   `feedback_attachment` row per screenshot (`data BYTEA`, `ON DELETE CASCADE`).
   No new infrastructure; screenshot and report share one DB, one transaction, one
   backup. Accepted trade-off: blobs live in-row, so they are never selected on the
   list/detail path — attachment **metadata** is read via a no-`data` projection query
   and the bytes only through the dedicated attachment endpoint.

2. **Transport: base64-in-JSON.** The portal wrapper only does `JSON.stringify`, so
   screenshots ride base64-encoded in the request body and the service decodes to raw
   bytes. Storage stays `bytea`; only the wire form is base64. This avoids adding a
   multipart path (and its CSRF wiring) to the portal client.

3. **Content trust: server-derived MIME by magic bytes.** The client's `Content-Type`
   is never trusted. The service inspects the decoded bytes' magic number, accepts only
   PNG/JPEG, and stores a **server-normalized** MIME. That MIME is echoed on the
   attachment-bytes response together with `X-Content-Type-Options: nosniff` and
   `Content-Disposition: inline; filename*=UTF-8''<sanitized>` (inline so an `<img>`
   renders it; a mislabeled/non-image payload can never be served back as HTML/JS).

4. **Size limits in two rubrics.** Per-file (~2 MiB) and per-request count (≤3) are
   enforced authoritatively in the service (a coarse `@Size` pre-guards the DTO). The
   raw body is capped twice: the portal gateway (primary, internet-facing) and a CRS
   ingress guard (`FeedbackRequestSizeFilter`, second line) that returns `413` for both
   an over-cap `Content-Length` and a chunked body that exceeds the cap mid-read. All
   limits are configurable via `components-registry.feedback.*`.

5. **Authorization split.** Submit (`POST /rest/api/4/feedback`) is authenticated-only
   (filter-chain `authenticated()` → anonymous is 401); the submitter username is taken
   from the JWT, never the body. Admin reads/triage (`/rest/api/4/admin/feedback**`)
   are **IMPORT_DATA**-gated (admin-only, ROLE_ADMIN). `ACCESS_AUDIT` is deliberately
   NOT used — viewers and editors hold it, so it would leak all reports (submitter
   identities, screenshots) to non-admins. Status changes stamp `updated_by` from the
   JWT.

6. **Retention.** RESOLVED reports (and their `bytea` via cascade) are pruned by a
   scheduled job when `updated_at` is older than `retention-days` (default 180);
   `retention-days <= 0` disables the prune. Mirrors the `service_event` retention
   pattern.

## Announcements (portal-owned; here only for the cross-link)

The paired "What's new" mechanism (config-as-code manifest, per-user localStorage,
same-origin media serving, first-open modal + feature spotlight) is entirely a portal
concern with no CRS storage, and is documented in the portal repo — not here. The only
tie to CRS is that the first announcement points at this feedback feature.

## Consequences

- No object store to operate; feedback ships on the existing schema (`V5__add_feedback.sql`).
- Screenshot bytes never load on list/detail (projection), so the admin list stays cheap.
- A non-image or oversized upload fails fast and cannot be served back as an active type.
- If screenshots ever grow large or numerous, moving `bytea` → object storage is a
  contained change behind `FeedbackService.getAttachment` + the attachment table.
