# TD-017: Tighten the v4 `@PreAuthorize` fitness check to assert a restrictive policy

## Status

Open. Deferred in the ArchUnit fitness-functions change (PR #439); the limitation is documented at
`beGuardedByPreAuthorize` in `ArchitectureFitnessTest`, which references this entry.

## Background

Rule 2 (`v4EndpointsMustBeAuthorized`) requires every v4 HTTP endpoint (scoped by request path
`rest/api/4/**`) to declare an authorization policy. The check is **presence-only**: it verifies a
`@PreAuthorize` annotation exists on the method or its declaring controller, NOT that the SpEL
expression is actually restrictive.

A future `@PreAuthorize("permitAll()")` (or `"true"`) would therefore satisfy the rule while being
effectively public. Today every `@PreAuthorize` in the codebase uses a real permission check, so
this is a latent gap, not a current one — but the rule would not catch a regression into a
permissive expression.

## Target

Strengthen the `beGuardedByPreAuthorize` condition to read the `@PreAuthorize` `value` (SpEL) and
reject known-permissive expressions:

- Fail on `permitAll()` / `permitAll` / a literal `true`.
- Optionally allow-list the permission-evaluator call shapes actually used (e.g.
  `@permissionEvaluator...`, `hasAuthority(...)`) and fail anything else, so new endpoints must use
  a recognised guard.

Keep the method-or-controller resolution already implemented.

## Effort / notes

- ArchUnit exposes annotation attributes via `tryGetAnnotationOfType(PreAuthorize::class.java)`;
  the `value` is the SpEL string to inspect.
- Pair with a regression fixture in `ArchitectureFitnessRegressionTest` (a `rest/api/4` endpoint
  annotated `@PreAuthorize("permitAll()")`) asserting the tightened rule flags it.
- No production code change expected — this only hardens the test-time gate.
