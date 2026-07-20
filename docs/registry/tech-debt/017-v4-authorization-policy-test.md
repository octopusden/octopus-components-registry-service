# TD-017: Verify v4 endpoint authorization policy via a Spring-context test

## Status

Open. The ArchUnit "every v4 endpoint must have `@PreAuthorize`" rule was **removed** (it did not
match the real security model and had drifted into re-implementing Spring's request-mapping
resolution). This TD tracks the correct replacement: a runtime, mapping-aware policy check.

## Background

Authorization in CRS is **hybrid**, not uniformly method-security:

- some endpoints use method-level `@PreAuthorize`;
- some are covered by filter-chain matchers (`authenticated()` / permission rules) in
  `WebSecurityConfig`;
- some are intentionally public (`info`, version `preview`, `migration-status`, feedback `submit`);
- the service-event ingest is guarded by a shared-secret check inside the controller;
- anonymous component reads pass through a permission granted to `ROLE_ANONYMOUS`.

A static "must carry `@PreAuthorize`" rule therefore mis-classifies several deliberate mechanisms as
"unguarded", and cannot see filter-chain protection at all. Worse, deciding "is this a v4 endpoint"
from bytecode required stitching class/method paths, composed annotations, meta-annotations,
inheritance and `@AliasFor` — i.e. partially re-doing Spring's own mapping resolution. That is the
wrong altitude: if you must reimplement Spring mapping resolution to check Spring mappings, check
them through Spring.

## Target

A `@SpringBootTest` (or `@WebMvcTest`)-based fitness test that:

1. Enumerates the REAL endpoints from `RequestMappingHandlerMapping` and filters to `rest/api/4/**`.
2. Requires each v4 mapping to carry exactly one explicit **policy classification**, via marker
   annotations rather than an implicit convention, e.g.:
   - `@PreAuthorize(...)` — method security;
   - `@FilterChainSecured` — protected by the filter chain (authentication / permission matcher);
   - `@PublicV4Endpoint` — intentionally public;
   - `@SharedSecretEndpoint` — special in-controller secret.
3. Fails if a v4 mapping has no classification, or more than one.

This turns today's five baseline "violations" into explicit, self-documenting architectural
decisions, and removes the need for any path/annotation resolution in test code. Optionally assert
each classification is consistent with `WebSecurityConfig` (e.g. `@FilterChainSecured` paths are
actually matched there).

## Effort / notes

- Marker annotations are cheap to add and make the security intent greppable at each endpoint.
- This test needs a Spring context, so it runs outside the fast no-context `test` gate — place it
  accordingly.
- Supersedes the removed ArchUnit rule and the (also removed) TD about ArchUnit's inability to
  resolve composed/inherited mappings — Spring resolves its own mappings, so those gaps vanish.
