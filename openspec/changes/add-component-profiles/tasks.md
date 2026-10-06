## 1. Baseline

- [ ] 1.1 Characterization tests on today's create and update: a solution created as `payments`,
      a regular component created as `resolution-service`, a rename to `payments-solution`, and
      unflagging `payments-solution` — all accepted today, and still accepted after this change

## 2. Profile parsing (Decisions 1, 5)

- [ ] 2.1 Write failing unit tests for `ComponentProfileParser`:
  - [ ] 2.1.1 The four profiles of the design example parse to four live profiles
  - [ ] 2.1.2 Each required key missing (`kind`, `title`, `description`, `order`,
        `classification.external`, `classification.explicit`) → problem naming it
  - [ ] 2.1.3 Unknown key at profile, `classification` and rule level → problem naming it
  - [ ] 2.1.4 Bad values: `kind: special`, blank `title` or `description`,
        `explicit: maybe`, `order: ten`, `external: yes` → problem naming key and value
  - [ ] 2.1.5 Id with an upper-case letter or `_` → invalid
  - [ ] 2.1.6 `solution` absent → `false`; `solution: true` with `explicit: ask` or
        `external: false` → invalid
  - [ ] 2.1.7 Rule with a non-compiling pattern, a blank or missing `message`, an extra key, or a
        path outside the spec's path list → invalid, naming the path; each listed path accepted
  - [ ] 2.1.8 Several problems in one profile → all reported
  - [ ] 2.1.9 `kind: template` → failed with "templates are not supported yet" and not checked
        further (its `parameters`, `fields` are not reported as unknown keys); others live
  - [ ] 2.1.10 No `regular` profile, or one invalid `regular` profile → configuration unusable,
        every entry listed with its status
- [ ] 2.2 Write failing tests for `ComponentProfilesSource` with YAML property sources (Decision 1):
  - [ ] 2.2.1 Keys arrive exactly as written: id `regular_external` and `Regular-External` kept,
        so the parser can reject them
  - [ ] 2.2.2 Dotted and indexed rule paths without brackets (`baseConfiguration.jira.projectKey`,
        `artifactIds[0].groupPattern`) arrive as one path
  - [ ] 2.2.3 Two sources (base and profile file): the higher-precedence value wins per key, keys
        from both are kept
  - [ ] 2.2.4 Numbers and booleans arrive typed or as strings; absent subtree → empty map
- [ ] 2.3 Implement:
  - [ ] 2.3.1 `profile/ComponentProfile.kt` — profile, classification (`Explicit` with `ASK`),
        field rule
  - [ ] 2.3.2 `profile/CreateRequestPaths.kt` — the free-text path list and `read(request, path)`
        (Decision 8); one test per listed path reading the value from a create request
  - [ ] 2.3.3 `profile/ComponentProfileParser.kt` — pure parse to profiles + entry statuses +
        configuration problems
  - [ ] 2.3.4 `profile/ComponentProfilesSource.kt` — flat keys from the enumerable property
        sources, highest precedence first
- [ ] 2.4 Confirm tests pass (record the command and count)

## 3. Catalog, startup and reload (Decisions 2, 3, 4)

- [ ] 3.1 Write failing tests:
  - [ ] 3.1.1 Catalog: usable load replaces the whole snapshot; a load with an invalid
        `regular` profile keeps the whole previous snapshot, including profiles whose change was
        valid, and returns the problems
  - [ ] 3.1.2 Context with no `regular` profile, or an invalid one, fails to start with the
        problems in the message
  - [ ] 3.1.3 Context with valid profiles and a template entry starts
  - [ ] 3.1.4 `POST /admin/reload-config` after a valid change → 200 with
        `componentProfiles.status: applied`, `status` and `changedKeys` unchanged
  - [ ] 3.1.5 Reload changing one title and breaking another `regular` profile → 422
        `error: component-profiles`, the broken entry `failed` with its problems, and the
        listing still returns the previous title
  - [ ] 3.1.6 Reload adding a template entry → 200, entry `failed` in `entries`
  - [ ] 3.1.7 Corrected configuration reloaded after a failure → applied
  - [ ] 3.1.8 No-db mode starts and reloads profiles the same way
  - [ ] 3.1.9 Reload with an invalid `field-config` → 422 `config-validation`, and the profiles
        are still reloaded and reported in `componentProfiles`
  - [ ] 3.1.10 Two concurrent reloads each return their own outcome
- [ ] 3.2 Implement:
  - [ ] 3.2.1 `profile/ComponentProfileCatalog.kt` — `AtomicReference<ProfileSnapshot>`, load in
        the initializer, `reload()` returning the outcome
  - [ ] 3.2.2 `AdminControllerV4.reloadConfig` — `refresh()`, then `catalog.reload()` in a
        `finally`; add `componentProfiles`; 422 on a failed load (Decision 4)
- [ ] 3.3 Add a minimal valid profile set to every configuration that starts the server: test,
      integration-test, smoke, `-test-db*`, `ft-db`, `no-db`, dev profiles and the client
      modules' test configs; none in the bundled `application.yml`
- [ ] 3.4 Confirm tests pass (record the command and count)

## 4. Listing (Decision 6)

- [ ] 4.1 Write failing tests:
  - [ ] 4.1.1 `ProfileAvailability`: with `CREATE_COMPONENTS` → usable; without → unusable with
        the reason
  - [ ] 4.1.2 `GET /rest/api/4/component-profiles`: sorted by `order` then id; fields present;
        failed entries absent; 403 without `ACCESS_COMPONENTS`
  - [ ] 4.1.3 Each profile's rules returned with path, pattern and message; a profile without
        rules returns an empty list
  - [ ] 4.1.4 After an applied reload that changes a pattern, the listing and a create with that
        profile both use the new pattern
- [ ] 4.2 Implement `profile/ProfileAvailability.kt`, response DTOs and
      `controller/ComponentProfileControllerV4.kt`
- [ ] 4.3 Confirm tests pass (record the command and count)

## 5. Profile on create (Decisions 7, 8)

- [ ] 5.1 Write failing tests through `POST /rest/api/4/components`, one per scenario of the
      "Optional profile on create" and "Field rules" requirements, plus:
  - [ ] 5.1.1 Template id as `profile` → 400 `profile: `
  - [ ] 5.1.2 Absent `distributionExternal` against `external: true` → 400 naming `external`
  - [ ] 5.1.3 Rule on an absent field with a pattern that rejects empty → 400 with the path
  - [ ] 5.1.4 A Portal-shaped request without `profile` → 201 as today
  - [ ] 5.1.5 `resolution-service` with `regular-internal` → 400 with the regular profile's
        message; `payments-solution` with `solution` → 201
  - [ ] 5.1.6 An unusable profile (availability stubbed) → 403 with the reason (Decision 6)
  - [ ] 5.1.7 `component.solution` hidden, `profile: solution` with `solution: true` → 400
        `profile: ` naming `solution`; same for hidden `distributionExternal` against
        `external: true`
  - [ ] 5.1.8 `name: " payments-solution "` with `profile: solution` → the rule passes
  - [ ] 5.1.9 Rule on `baseConfiguration.vcsEntries[0].vcsPath`, two entries, only the second
        breaking it → accepted
- [ ] 5.2 Implement: `ComponentCreateRequest.profile`; `profile/ProfileCreateCheck.kt` (lookup,
      availability, classification, rules); call it from `createComponent` before the flush
- [ ] 5.3 Confirm tests pass (record the command and count)

## 6. Docs and contract

- [ ] 6.1 Regenerate `v4.json` (`generateOpenApiDocs`); `OpenApiV4SpecTest` green
- [ ] 6.2 `docs/registry/api-changelog.md` — Unreleased: the listing endpoint, `profile` on create,
      the reload response; no behavior change for creates without `profile`
- [ ] 6.3 `docs/registry/functional-spec.md` — profiles and their field rules
- [ ] 6.4 ADR-016 — `component-profiles` as a third subtree: read from the property sources (so
      rule paths need no bracket notation, unlike `field-config`), snapshot and the
      no-partial-apply rule
- [ ] 6.5 Tech-debt records in `docs/registry/tech-debt/` for the limitations this change leaves
      in place, each `Open` with Context, The limit, Removal options and References, numbered from
      the next free `TD-NNN` at implementation time:
  - [ ] 6.5.1 Solution key rules hold only on a create that names a profile — a create without
        `profile`, a rename and a solution-flag change are not checked against them
  - [ ] 6.5.2 The remaining Portal-only create rules (Jira project key and full version format,
        VCS path, branch and tag unless the build system needs no VCS, `ssh://` VCS path on the
        configured host, artifact-ownership group-ID prefix, complete distribution coordinate) are
        not enforced by the registry, so an API create can skip them
  - [ ] 6.5.3 A reload is not atomic across configuration subtrees — `field-config` and
        `component-defaults` are applied even when the profiles fail

## 7. Finalization

- [ ] 7.1 `./gradlew build` and `qualityStatic` green; coverage floors unchanged
- [ ] 7.2 Out-of-scope boundaries hold: no template parsing beyond the failed entry, no D&S rule,
      no change to the update, import or field-override paths, no global naming convention,
      no migration (checked by diff)
- [ ] 7.3 Risks in `design.md` still stated, and each accepted limitation has its tech-debt
      record from 6.5
