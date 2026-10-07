## 1. Baseline

- [x] 1.1 Characterization tests on today's create and update: a solution created as `payments`,
      a regular component created as `resolution-service`, a rename to `payments-solution`, and
      unflagging `payments-solution` — all accepted today, and still accepted after this change.
      `SolutionKeyWithoutProfileTest` (`@Tag("integration")`, so it runs in `dbTest` on H2
      `ft-db`): 4/4 green on the unchanged code

## 2. Profile parsing (Decisions 1, 5)

- [x] 2.1 Write failing unit tests for `ComponentProfileParser`:
  - [x] 2.1.1 The four profiles of the design example parse to four live profiles
  - [x] 2.1.2 Each required key missing (`kind`, `title`, `description`, `order`,
        `classification.external`, `classification.explicit`) → problem naming it
  - [x] 2.1.3 Unknown key at profile, `classification` and rule level → problem naming it
  - [x] 2.1.4 Bad values: `kind: special`, blank `title` or `description`,
        `explicit: maybe`, `order: ten`, `external: yes` → problem naming key and value
  - [x] 2.1.5 Id with an upper-case letter or `_` → invalid
  - [x] 2.1.6 `solution` absent → `false`; `solution: true` with `explicit: ask` or
        `external: false` → invalid
  - [x] 2.1.7 Rule with a non-compiling pattern, a blank or missing `message`, an extra key, or a
        path outside the spec's path list → invalid, naming the path; each listed path accepted
  - [x] 2.1.8 Several problems in one profile → all reported
  - [x] 2.1.9 `kind: template` → failed with "templates are not supported yet" and not checked
        further (its `parameters`, `fields` are not reported as unknown keys); others live
  - [x] 2.1.10 No `regular` profile, or one invalid `regular` profile → configuration unusable,
        every entry listed with its status
- [x] 2.2 Write failing tests for `ComponentProfilesSource` with YAML property sources (Decision 1):
  - [x] 2.2.1 Keys arrive exactly as written: id `regular_external` and `Regular-External` kept,
        so the parser can reject them
  - [x] 2.2.2 Dotted and indexed rule paths without brackets (`baseConfiguration.jira.projectKey`,
        `artifactIds[0].groupPattern`) arrive as one path
  - [x] 2.2.3 Two sources (base and profile file): the higher-precedence value wins per key, keys
        from both are kept
  - [x] 2.2.4 Numbers and booleans arrive typed or as strings; absent subtree → empty map. The
        source hands the parser the resolved string either way (design Decision 1)
- [x] 2.3 Implement:
  - [x] 2.3.1 `profile/ComponentProfile.kt` — profile, classification (`Explicit` with `ASK`),
        field rule
  - [x] 2.3.2 `profile/CreateRequestPaths.kt` — the free-text path list and `read(request, path)`
        (Decision 8); one test per listed path reading the value from a create request
  - [x] 2.3.3 `profile/ComponentProfileParser.kt` — pure parse to profiles + entry statuses +
        configuration problems
  - [x] 2.3.4 `profile/ComponentProfilesSource.kt` — flat keys from the enumerable property
        sources, highest precedence first
- [x] 2.4 Confirm tests pass. `./gradlew :components-registry-service-server:test --tests "*.profile.*"`:
      `ComponentProfileParserTest` 39, `ComponentProfilesSourceTest` 8, `CreateRequestPathsTest`
      23, all green. Full module `test`: 1235 tests, 0 failures, 1 skipped. `detekt` and
      `ktlintCheck` clean

## 3. Catalog, startup and reload (Decisions 2, 3, 4)

- [x] 3.1 Write failing tests:
  - [x] 3.1.1 Catalog: usable load replaces the whole snapshot; a load with an invalid
        `regular` profile keeps the whole previous snapshot, including profiles whose change was
        valid, and returns the problems
  - [x] 3.1.2 Context with no `regular` profile, or an invalid one, fails to start with the
        problems in the message
  - [x] 3.1.3 Context with valid profiles and a template entry starts
  - [x] 3.1.4 `POST /admin/reload-config` after a valid change → 200 with
        `componentProfiles.status: applied`, `status` and `changedKeys` unchanged
  - [x] 3.1.5 Reload changing one title and breaking another `regular` profile → 422
        `error: component-profiles`, the broken entry `failed` with its problems, and the
        listing still returns the previous title (checked on the catalog; the listing endpoint
        comes in section 4)
  - [x] 3.1.6 Reload adding a template entry → 200, entry `failed` in `entries`
  - [x] 3.1.7 Corrected configuration reloaded after a failure → applied
  - [x] 3.1.8 No-db mode starts with the profiles loaded. Changed during implementation: no-db
        mode has no reload endpoint (`AdminControllerV4` is `@ConditionalOnDatabaseEnabled`), so
        its profiles change only with a restart — `NoDbModeContextTest` pins both (design Decision 3)
  - [x] 3.1.9 Reload with an invalid `field-config` → 422 `config-validation`, and the profiles
        are still reloaded and reported in `componentProfiles`
  - [x] 3.1.10 Two concurrent reloads each return their own outcome (catalog level,
        `ComponentProfileCatalogTest`)
  - [x] 3.1.11 A reload whose configuration cannot be read keeps the profiles in use and reports
        why (added during implementation)
- [x] 3.2 Implement:
  - [x] 3.2.1 `profile/ComponentProfileCatalog.kt` — `AtomicReference` over the live profile list
        (no separate snapshot type), load in the initializer, `reload()` returning the outcome
  - [x] 3.2.1a `config/ComponentProfilesConfig.kt` — source and catalog as beans in every mode,
        not `@ConfigurationProperties` (added on review)
  - [x] 3.2.2 `AdminControllerV4.reloadConfig` — `refresh()`, then `catalog.reload()` in a
        `finally`; add `componentProfiles`; 422 on a failed load (Decision 4). Response part is
        `dto/v4/ComponentProfilesReloadResponse`
- [x] 3.3 Add a minimal valid profile set to every configuration that starts the server: test,
      integration-test, smoke, `-test-db*`, `ft-db`, `no-db`, dev profiles and the client
      modules' test configs; none in the bundled `application.yml`. Every test context activates
      `common`, so the sets live in: the server's test `application-common.yml` (the four
      profiles), the client and light-client test `application-common.yml` and
      `application-integration-test.yml` (one regular profile each), and main
      `application-dev.yml` (the four; also used by the compat candidate)
  - [x] 3.3.1 The OKD functional-test pod (`components-registry-automation/data/components-registry-service.yaml`,
        mounted as `application-test.yaml`) gets the four profiles too: TeamCity's Compile & UT
        deploys it and it failed readiness with "at least one regular profile is required"
        (added post-review)
- [x] 3.4 Confirm tests pass. Server `test` 1247 (1 skipped, 0 failures, incl.
      `ComponentProfileCatalogTest` 8, `ComponentProfilesConfigTest` 3, `NoDbModeContextTest`);
      `integrationTest` 3/3; client `test` 6/6; light-client `test` 2/2; `detekt`, `ktlintCheck`
      clean. `dbTest` on H2: `ReloadConfigComponentProfilesTest` 5/5, `SolutionKeyWithoutProfileTest`
      4/4. Not verified locally: the 182 `dbTest` cases that need Testcontainers Postgres failed
      because Docker was not running (every failure traces to "Could not find a valid Docker
      environment", none to the profiles) — to run with Docker up or on CI

## 4. Listing (Decision 6)

- [x] 4.1 Write failing tests:
  - [x] 4.1.1 `ProfileAvailability`: with `CREATE_COMPONENTS` → usable; without → unusable with
        the reason
  - [x] 4.1.2 `GET /rest/api/4/component-profiles`: sorted by `order` then id; fields present;
        failed entries absent; 403 without `ACCESS_COMPONENTS`
  - [x] 4.1.3 Each profile's rules returned with path, pattern and message; a profile without
        rules returns an empty list
  - [x] 4.1.4 After an applied reload that changes a pattern, the listing and a create with that
        profile both use the new pattern. Listing half here; the create half is 5.1.10, once
        create checks the profile
- [x] 4.2 Implement `profile/ProfileAvailability.kt`, response DTOs and
      `controller/ComponentProfileControllerV4.kt`. Response: `{ profiles: [...] }`
      (`dto/v4/ComponentProfileResponse.kt`), each with `usable` and `unusableReason`
  - [x] 4.2.1 `OpenApiV4Config` matches `/rest/api/4/component-profiles` and `v4.json` is
        regenerated in this step: the v4 group lists its paths, and `OpenApiV4SpecTest` runs in
        `test`, so a contract change carries its spec (added during implementation)
- [x] 4.3 Confirm tests pass. `PermissionProfileAvailabilityTest` 2/2; `dbTest`
      `ComponentProfileControllerV4Test` 9/9 (H2); server `test` 1249, 0 failures, 1 skipped
      (incl. `OpenApiV4SpecTest`, `ArchitectureFitnessTest`); `detekt`, `ktlintCheck` clean

## 5. Profile on create (Decisions 7, 8)

- [x] 5.1 Write failing tests through `POST /rest/api/4/components`, one per scenario of the
      "Optional profile on create" and "Field rules" requirements, plus:
  - [x] 5.1.1 Template id as `profile` → 400 `profile: `
  - [x] 5.1.2 Absent `distributionExternal` against `external: true` → 400 naming `external`
  - [x] 5.1.3 Rule on an absent field with a pattern that rejects empty → 400 with the path
  - [x] 5.1.4 A Portal-shaped request without `profile` → 201 as today
  - [x] 5.1.5 `resolution-service` with `regular-internal` → 400 with the regular profile's
        message; `payments-solution` with `solution` → 201
  - [x] 5.1.6 An unusable profile (availability stubbed) → 403 with the reason (Decision 6)
  - [x] 5.1.7 `component.solution` hidden, `profile: solution` with `solution: true` → 400
        `profile: ` naming `solution`; same for hidden `distributionExternal` against
        `external: true`
  - [x] 5.1.8 `name: " payments-solution "` with `profile: solution` → the rule passes
  - [x] 5.1.9 Rule on `baseConfiguration.vcsEntries[0].vcsPath`, two entries, only the second
        breaking it → accepted
  - [x] 5.1.10 After an applied reload that changes a pattern, a create is checked against the
        new pattern (the create half of 4.1.4)
  - [x] 5.1.11 A blank `profile` is the same as no profile (added during implementation)
  - [x] 5.1.12 A rename of a component created with a profile is checked as today
- [x] 5.2 Implement: `ComponentCreateRequest.profile`; `profile/ProfileCreateCheck.kt` (lookup,
      availability, classification, rules); call it from `createComponent` before the flush
  - [x] 5.2.1 `v4.json` regenerated for `ComponentCreateRequest.profile`
  - [x] 5.2.2 `profile/CreatedComponent.kt` — the check reads the entity and base row the create
        built, not the request (changed during implementation, design Decision 8); its reader map
        is pinned to `CreateRequestPaths.PATHS`
- [x] 5.3 Confirm tests pass. `ProfileCreateCheckTest` 14/14; `ProfileOnCreateTest` 17/17 (H2);
      server `test` 1263, 0 failures, 1 skipped; every H2 `dbTest` class (67 classes, 580 tests,
      0 failures, 2 skipped) — the whole create path; client 6/6, light-client 2/2; `detekt`,
      `ktlintCheck` clean. Postgres `dbTest` classes not run locally (no Docker) — left to CI

## 6. Docs and contract

- [x] 6.1 Regenerate `v4.json` (`generateOpenApiDocs`); `OpenApiV4SpecTest` green. Done with each
      contract change (4.2.1, 5.2.1); final check: `OpenApiV4SpecTest` 1/1, no drift
- [x] 6.2 `docs/registry/api-changelog.md` — Unreleased: the listing endpoint, `profile` on create,
      the reload response; no behavior change for creates without `profile`
- [x] 6.3 `docs/registry/functional-spec.md` — profiles and their field rules: §1.3 (create with
      `profile`, the start-page item) and a new §7.4 (configuration, startup, reload, listing)
- [x] 6.4 ADR-016 — `component-profiles` as a third subtree: read from the property sources (so
      rule paths need no bracket notation, unlike `field-config`), snapshot and the
      no-partial-apply rule
- [x] 6.5 Tech-debt records in `docs/registry/tech-debt/` for the limitations this change leaves
      in place, each `Open` with Context, The limit, Removal options and References, numbered from
      the next free `TD-NNN` at implementation time (TD-024 to TD-026; no other branch claimed them):
  - [x] 6.5.1 Solution key rules hold only on a create that names a profile — a create without
        `profile`, a rename and a solution-flag change are not checked against them — TD-024
  - [x] 6.5.2 The remaining Portal-only create rules (Jira project key and full version format,
        VCS path, branch and tag unless the build system needs no VCS, `ssh://` VCS path on the
        configured host, artifact-ownership group-ID prefix, complete distribution coordinate) are
        not enforced by the registry, so an API create can skip them — TD-025
  - [x] 6.5.3 A reload is not atomic across configuration subtrees — `field-config` and
        `component-defaults` are applied even when the profiles fail — TD-026

## 7. Finalization

- [x] 7.1 `./gradlew build` and `qualityStatic` green; coverage floors unchanged. Locally `build`
      does not configure without OKD auth-server settings, so ran `test qualityStatic` across all
      modules (placeholder `-Pauth-server.*`): every module's tests green (server 1263, cli 115,
      resolver-core 175, validation 170, …), detekt / ktlint / checkstyle / PMD clean. Three tasks
      failed outside this change: `components-registry-automation:compileTestKotlin` (cannot
      resolve the `kotlin-test` JUnit 5 variant, same on the base), `dockerBuildImage` (no Docker),
      and `component-resolver-core:test`, whose JVM crashes in the JIT (SIGSEGV in
      `BoolNode::Ideal`, local JDK 21.0.9) after its tests pass. No build file or PIT target
      changed, so the coverage floors are untouched. `build` itself runs on CI
  - [x] 7.1a SonarCloud flagged `classification.*` key literals repeated in
        `ComponentProfileParser`; named once as constants (added on review)
- [x] 7.2 Out-of-scope boundaries hold: no template parsing beyond the failed entry, no D&S rule,
      no change to the update, import or field-override paths, no global naming convention,
      no migration (checked by diff against `main`: `ComponentManagementServiceImpl` changes only
      imports, the constructor and `createComponent`; no migration, import, field-override or DSL
      file; D&S and templates appear only as the failed template entry and a KDoc note)
- [x] 7.3 Risks in `design.md` still stated, and each accepted limitation has its tech-debt
      record from 6.5 (each risk names TD-024, TD-025 or TD-026)
