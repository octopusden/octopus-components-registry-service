# TD-016: component-validation — version resolvers match against a hardcoded token list, not arbitrary versions

## Status

Open. The original concern this ticket tracked — marker-based resolvers missing unmarked/plain-path
values — is fixed for real-world values seen today (see below). What remains, for both resolvers,
is that they recognize versions from a fixed, hardcoded list rather than parsing the version
generically — so a *new* tool version silently resolves to `null` until someone updates the code.

## Context

`JavaVersionResolver` and `MavenVersionResolver` (`ValueVersionResolver<V>` implementations)
derive a tool version from an already reference-resolved parameter value:

- `JavaVersionResolver` treats any value containing a `jdk`/`java`/`jvm` token (case-insensitive)
  as a Java reference, covering both env-var-style names (`env.JDK_ORACLE_17_x64`), resolved
  directory paths on Linux/Windows (`/usr/lib/jvm/java-21-openjdk-21.0.11...`, `C:\Java\RedHat\17`),
  and `%env.BUILD_ENV%/JAVA/<version` references.
  It only recognizes a fixed list of tokens: `1.8`, `8`, `11`, `17`, `21`, `25` (plus the `18`
  legacy alias for `1.8`). This currently covers every Java version this org is known to use — but
  the day a new LTS (e.g. Java 29) shows up in a build config, it resolves to `null` and is
  silently invisible to `USES_OLD_JAVA_VERSION`/`MULTIPLE_JAVA_VERSIONS` until `TOKENS` is updated.
- `MavenVersionResolver` treats any value containing a `maven` token as a Maven reference (e.g.
  `apache-maven-3.6.3`), against an even narrower fixed list: `3.6.3`, `3.6.0`, `3.3.9`, `LATEST`,
  and a bare `3` fallback.

Neither resolver emits any signal when a recognized marker is present but no known token is (it
silently resolves to `null`).

## Why this matters

Both checks depend on the tool version resolving correctly:

- `MULTIPLE_MAVEN_VERSIONS` can under-report or coarsen distinct Maven versions *today*, for
  versions already in use outside the current token list (`3.8.x`, `3.9.x`, `4.x`).
- `USES_OLD_JAVA_VERSION` / `MULTIPLE_JAVA_VERSIONS` have no known false negative today, but are
  one new Java release away from one: nothing fails loudly when a build config references a Java
  version this module doesn't yet know about, it just silently doesn't count.

Because both resolvers are hardcoded token lists rather than generic version parsers, this is a
recurring maintenance burden, not a one-time fix — every new Java/Maven release needs a code
change here before validation can see it.

## Decision

Ship the current token-list resolvers as-is for now; in a later iteration:

- Extend `MavenVersionResolver` to parse arbitrary `X.Y.Z` version strings following a `maven`
  marker, instead of matching a fixed token list — this closes today's known gap.
- Consider the same generic-parsing approach for `JavaVersionResolver` (extract any digit-bounded
  major version, or `1.<major>`, after a `jdk`/`java`/`jvm` marker) so a future Java release
  doesn't require a code change to be detected.
- Add visibility (log, or a distinct signal) for a recognized marker with no resolvable version,
  for both resolvers, so a miss becomes detectable instead of silently dropping to `null`.

## Acceptance criteria (to close this ticket)

- [ ] Extend `MavenVersionResolver` to extract arbitrary Maven version strings rather than
      matching a fixed token allowlist, without regressing the existing cases.
- [ ] Extend or replace `JavaVersionResolver`'s fixed token list with generic major-version
      extraction, so a new Java release resolves without a code change.
- [ ] Add a visible signal (log or status) when a recognized marker resolves no version, for both
      resolvers.

## Risk classification

Medium — `MULTIPLE_MAVEN_VERSIONS` can already under-report or coarsen Maven versions outside the
current token list. `USES_OLD_JAVA_VERSION` / `MULTIPLE_JAVA_VERSIONS` have no active false
negative today, but will silently miss the next new Java version until this is addressed.

## See also

- `component-validation/src/main/kotlin/org/octopusden/octopus/validation/resolvers/teamcity/value/impl/JavaVersionResolver.kt`
- `component-validation/src/main/kotlin/org/octopusden/octopus/validation/resolvers/teamcity/value/impl/MavenVersionResolver.kt`
