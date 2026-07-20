# TD-018: Close the residual v4-endpoint-detection gaps in the ArchUnit security rule

## Status

Open. The v4 authorization fitness rule (`ArchitectureFitnessTest.v4EndpointsMustBeAuthorized`)
scopes endpoints by request path OR `*ControllerV4` class name and detects endpoint methods via
`@RequestMapping` meta-annotations (so Spring composed annotations are covered). A few narrow cases
remain where an unguarded v4 endpoint could still slip past the rule.

## Background

`isV4HttpEndpoint` decides "is this a v4 endpoint" from information available on the `JavaMethod`
and its declaring class:

- endpoint = method (meta-)annotated with `@RequestMapping`;
- v4 = effective path (declaring-class `@RequestMapping` base + method mapping) under `rest/api/4`,
  OR declaring class named `*ControllerV4`.

This covers every controller in the module today. It does NOT cover, when the class name is not
`*ControllerV4`:

1. **Composed annotation carrying the `rest/api/4` path only at the METHOD level.** The path is
   inside the custom annotation's attributes, which the rule does not read (`mappingPaths` handles
   only the six standard types), so the method sub-path resolves to empty → not classified as v4.
2. **Composed annotation carrying the `rest/api/4` base at the CLASS level** (e.g. `@Rest4Api`
   meta-annotated with `@RequestMapping("rest/api/4")`, with a standard `@GetMapping` on the method).
   `classMappingPaths` reads only the DIRECT `@RequestMapping` of the declaring class, so a composed
   class-level mapping yields an empty base path → not classified as v4.
3. **Endpoint methods that inherit their class-level mapping from an abstract base.** ArchUnit's
   `JavaMethod.owner` is the declaring (base) class, so the concrete subclass's class-level
   `@RequestMapping` base is not stitched in (the existing v1/v2 `BaseComponentController` idiom).
   A future v4 controller reusing that idiom would have its inherited endpoints mis-scoped.

(Endpoint DETECTION already follows meta-annotations, so composed method mappings are still seen as
HTTP endpoints — the gap above is purely in resolving their PATH, i.e. the v4 classification.)

Neither case exists in the codebase now (all 12 v4 controllers are standalone final classes named
`*ControllerV4` with a class-level `rest/api/4` `@RequestMapping` and standard method mappings), so
this is latent, not an active hole.

## Target

Resolve v4 scoping the way Spring resolves request mappings, rather than from the declaring class
alone. Options, in rough order of robustness:

- Read the effective path from composed annotations too — at BOTH the class and method level —
  following `@AliasFor`/`value`/`path` through the meta-annotation chain, not just the six
  enumerated types read directly off the element.
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
