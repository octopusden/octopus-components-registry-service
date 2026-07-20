# TD-018: Close the residual v4-endpoint-detection gaps in the ArchUnit security rule

## Status

Open. The v4 authorization fitness rule (`ArchitectureFitnessTest.v4EndpointsMustBeAuthorized`)
scopes endpoints by request path OR `*ControllerV4` class name and detects endpoint methods via
`@RequestMapping` meta-annotations (so Spring composed annotations are covered). Two narrow cases
remain where an unguarded v4 endpoint could still slip past the rule.

## Background

`isV4HttpEndpoint` decides "is this a v4 endpoint" from information available on the `JavaMethod`
and its declaring class:

- endpoint = method (meta-)annotated with `@RequestMapping`;
- v4 = effective path (declaring-class `@RequestMapping` base + method mapping) under `rest/api/4`,
  OR declaring class named `*ControllerV4`.

This covers every controller in the module today. It does NOT cover:

1. **Composed annotation carrying the full `rest/api/4` path only at the method level, in a class
   that is neither named `*ControllerV4` nor has a class-level `rest/api/4` `@RequestMapping`.** The
   path is inside the custom annotation's attributes, which the rule does not read, so the effective
   path resolves to empty and the name signal is absent → the endpoint is not classified as v4.
2. **Endpoint methods that inherit their class-level mapping from an abstract base.** ArchUnit's
   `JavaMethod.owner` is the declaring (base) class, so the concrete subclass's class-level
   `@RequestMapping` base is not stitched in (the existing v1/v2 `BaseComponentController` idiom).
   A future v4 controller reusing that idiom would have its inherited endpoints mis-scoped.

Neither case exists in the codebase now (all 12 v4 controllers are standalone final classes named
`*ControllerV4` with a class-level `rest/api/4` `@RequestMapping` and standard method mappings), so
this is latent, not an active hole.

## Target

Resolve v4 scoping the way Spring resolves request mappings, rather than from the declaring class
alone. Options, in rough order of robustness:

- Read the effective path from composed annotations too (follow `@AliasFor`/`value`/`path` through
  the meta-annotation chain), not just the six enumerated types.
- Resolve the CONCRETE controller bean for an inherited endpoint method (walk subclasses) so the
  subclass's class-level `@RequestMapping` base is applied.
- Alternatively, add a **separate, active** ArchUnit rule that FORBIDS the risky shapes in
  `..controller..` (e.g. an endpoint method whose declaring class has no resolvable class-level
  mapping and is not `*ControllerV4`), so such a controller fails the build loudly instead of
  silently escaping the security gate.

## Effort / notes

- Add regression fixtures in `ArchitectureFitnessRegressionTest` for each closed case (a composed
  method-level `rest/api/4` mapping on a plainly-named class; an inherited-mapping v4 controller).
- Until then, the belt-and-suspenders `*ControllerV4` name check plus the class-path check make the
  gap require BOTH an unconventional annotation shape AND an off-convention class name at once.
