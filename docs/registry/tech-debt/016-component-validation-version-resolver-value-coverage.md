# TD-016: component-validation — version resolvers only recognize marker-based values; unmarked/plain-path values are silently ignored

## Status

Open · deferred enhancement, explicitly parked for a later iteration (user decision during the
server-integration design). This is a distinct, narrower concern: the *coverage* of the
value-parsing resolvers, not the parameter names / template ids.

## Context

Inside the `component-validation` module, the `ValueVersionResolver<V>` implementations derive a
tool version from an already reference-resolved parameter value:

- `JavaVersionResolver` recognizes a version **only** when the value contains one of two markers —
  `BUILD_ENV` or `env.JDK` — and then only for an allowlist of tokens (`1.8`, `17`, `21`, `25`).
- `MavenVersionResolver` recognizes a version **only** under a `BUILD_ENV` marker, for the tokens
  `3`, `3.3.9`, `3.6.0`, `3.6.3`, `LATEST`.

Any value without a recognized marker, or with a version outside the allowlist, resolves to `null`
and is silently ignored (per decision D7: unresolved versions are not flagged). Concretely, this
value observed in real usage resolves to `null`:

```
JAVA_HOME=/usr/lib/jvm/java-17
```

(a plain install path, no `BUILD_ENV` / `env.JDK` marker). Java `11` also resolves to `null`
(`BUILD_ENV_11` is explicitly rejected by the tests), as do build-suffixed or path-style values
like `1.8.0_392` and `/opt/java/openjdk-11`.

## Why this matters

For `USES_OLD_JAVA_VERSION` this is a correctness risk in the "wrong" direction: a Java **1.8**
expressed as an unmarked value or a plain path (e.g. `/usr/lib/jvm/java-8`, `jdk1.8.0_392`) would
resolve to `null` and be treated as "no old Java found" — a **silent false negative** for the exact
thing the check exists to catch. The allowlist is a deliberate, data-driven fit to the versions
this org is currently known to use, so it is acceptable for the first iteration, but it is fragile:
a new Java version, a new marker convention, or a plain-path value will be missed without any
signal.

## Decision

Ship the current marker-based, allowlist resolvers as-is for now. Enhance in a later iteration:

- Broaden `JavaVersionResolver` / `MavenVersionResolver` to also parse plain-path and
  build-suffixed value shapes (e.g. `.../java-17`, `.../jdk1.8.0_x`, `/opt/apache-maven-3.8.6`) and
  versions beyond the current allowlist (Java 11, etc.).
- Add visibility for unresolved-but-non-empty values (log, or a distinct signal) so a miss becomes
  detectable instead of silently dropping to `null`.

## Acceptance criteria (to close this ticket)

- [ ] Gather real parameter values (JDK/Maven) across `CDGradleBuild` / `CDJavaMavenBuild` and
      representative custom steps.
- [ ] Extend `JavaVersionResolver` to cover plain-path / build-suffixed forms and out-of-allowlist
      versions, without regressing the marker cases.
- [ ] Extend `MavenVersionResolver` similarly.
- [ ] Add a visible signal (log or status) when a non-empty value fails to resolve, so silent
      misses are detectable.
- [ ] Add a regression test proving a Java 1.8 expressed as a plain path is flagged by
      `USES_OLD_JAVA_VERSION`.

## Risk classification

Medium — until closed, `USES_OLD_JAVA_VERSION` can under-report Java 1.8 usage that is expressed in
a value shape the resolvers don't recognize. No structural/API impact; contained to the two
`ValueVersionResolver` implementations.

## See also

- `component-validation/src/main/kotlin/org/octopusden/octopus/validation/resolvers/teamcity/value/impl/JavaVersionResolver.kt`
- `component-validation/src/main/kotlin/org/octopusden/octopus/validation/resolvers/teamcity/value/impl/MavenVersionResolver.kt`
