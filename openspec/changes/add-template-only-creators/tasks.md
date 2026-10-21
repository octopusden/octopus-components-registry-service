## 1. Baseline

- [x] 1.1 Characterization tests on what this change alters, green on the unchanged code:
  - [x] 1.1.1 A user with `ROLE_COMPONENTS_REGISTRY_EDITOR` gets every profile and template usable
        in the listing, and may override a template field — new
        `ComponentProfileControllerV4Test.usableForEditor`; the override is already pinned by
        `ComponentTemplateControllerV4Test.invalidOverride`, which runs as an editor
  - [x] 1.1.2 A user with `ROLE_COMPONENTS_REGISTRY_VIEWER` gets every entry unusable with "You do
        not have permission to create components", and 403 on a template dry run — already pinned
        by `ComponentProfileControllerV4Test.unusableForViewer` and
        `ComponentTemplateControllerV4Test.viewerRefused`
  - [x] 1.1.3 `POST /rest/api/4/components` answers 403 to a viewer — already pinned by
        `UserInfoConverterIntegrationTest.viewerCannotCreateV4` (Postgres in Docker, so CI only)
- [x] 1.2 Green on the unchanged code: `ComponentProfileControllerV4Test` 11 and
      `ComponentTemplateControllerV4Test` 30, 0 failures (`dbTest`, H2)

## 2. Permission and role (Decision 1)

- [x] 2.1 Write failing tests, in a new `RoleMapTest` (H2, so it runs without Docker):
  - [x] 2.1.1 `GET /auth/me` with `ROLE_COMPONENTS_REGISTRY_TEMPLATE_CREATOR` lists
        `ACCESS_COMPONENTS`, `ACCESS_AUDIT` and `CREATE_FROM_TEMPLATE` for that role, read from the
        bundled role map
  - [x] 2.1.2 `ROLE_COMPONENTS_REGISTRY_EDITOR` and `ROLE_ADMIN` still list the same permissions
        as before, without `CREATE_FROM_TEMPLATE` (Decision 2)
- [x] 2.2 Implement:
  - [x] 2.2.1 `PermissionEvaluator` — `CREATE_FROM_TEMPLATE` in the companion, beside the others
  - [x] 2.2.2 bundled `application.yml` — `ROLE_COMPONENTS_REGISTRY_TEMPLATE_CREATOR`
  - [x] 2.2.3 `SecurityTestHelpers` — `templateCreatorJwt()`
- [x] 2.3 Confirm tests pass. `RoleMapTest` 3, 0 failures; 2.1.1 failed first with no role reported
      (the role was not in the map).

## 3. Availability (Decisions 2, 3)

- [x] 3.1 Write failing unit tests for `PermissionProfileAvailability`, one slice at a time:
  - [x] 3.1.1 `CREATE_COMPONENTS`: profile usable, template usable, override allowed — already
        pinned by `withCreatePermission` and `templateWithCreatePermission`
  - [x] 3.1.2 `CREATE_FROM_TEMPLATE` only: profile unusable with "You may create components
        from templates only", template usable, override refused — three slices, each red first:
        `templateForTemplateOnlyCreator`, `profileForTemplateOnlyCreator`,
        `overrideForTemplateOnlyCreator`
  - [x] 3.1.3 Both: profile usable, template usable, override allowed — `bothCreatePermissions`,
        green on its first run, since `CREATE_COMPONENTS` already decided
  - [x] 3.1.4 Neither: profile and template unusable with "You do not have permission to create
        components", override refused — already pinned by `withoutCreatePermission` and
        `templateWithoutCreatePermission`
- [x] 3.2 Implement:
  - [x] 3.2.1 `PermissionProfileAvailability` — the table in Decision 3, `CREATE_COMPONENTS` first;
        permission names from `PermissionEvaluator`. Refactor pass after green: each answer is one
        `when`, with the three outcomes as named constants
  - [x] 3.2.2 KDoc on `ProfileAvailability` — describe the template-only rule instead of "a later
        restriction", without naming a user group
- [x] 3.3 Confirm tests pass. `PermissionProfileAvailabilityTest` 8, 0 failures; `ktlintCheck` and
      `detekt` clean.

## 4. Endpoints with a template-only creator (Decisions 4, 5)

- [x] 4.1 Controller tests with `templateCreatorJwt()` (written after section 3, so green on their
      first run; checked against the pre-change `PermissionProfileAvailability`, where 4.1.1–4.1.3
      fail):
  - [x] 4.1.1 Listing: templates usable, regular profiles unusable with the template-only reason —
        `ComponentProfileControllerV4Test.templateOnlyCreator`
  - [x] 4.1.2 Template dry run without overrides: 200 —
        `ComponentTemplateControllerV4Test.templateOnlyCreatorDryRun`
  - [x] 4.1.3 Template create without overrides: 201, component created —
        `templateOnlyCreatorCreate`
  - [x] 4.1.4 Template dry run with an overridable override: 403, nothing created, on a dry run and
        a create — `templateOnlyCreatorOverridableOverride`
  - [x] 4.1.5 Template dry run with a non-overridable override: 403, not 400 (order 5 before 6) —
        `templateOnlyCreatorNotOverridableOverride`
  - [x] 4.1.6 `POST /rest/api/4/components` without a profile: 403, nothing created —
        `ProfileOnCreateTest.templateOnlyCreatorRefused`
  - [x] 4.1.7 `POST /rest/api/4/components` naming a regular profile: 403, nothing created — same
        test
  - [x] 4.1.8 `PATCH` of a component the user owns: allowed —
        `ComponentOwnershipEditSecurityTest.template only creator owner allowed`
  - [x] 4.1.4a 4.1.4 and 4.1.5 also pass on the pre-change rule, which refuses the template itself
        (order 4) instead of the override (order 5). They pin the 403; the step is pinned by
        `PermissionProfileAvailabilityTest.overrideForTemplateOnlyCreator` (added on review)
- [x] 4.2 Implement: no controller change was needed.
- [x] 4.3 Confirm tests pass. `dbTest` (H2): `RoleMapTest` 3, `ComponentProfileControllerV4Test` 12,
      `ComponentTemplateControllerV4Test` 34, `ProfileOnCreateTest` 18,
      `ComponentOwnershipEditSecurityTest` 20 — 0 failures.

## 5. Docs

- [x] 5.1 ADR-004 — the permission table and the role map gain `CREATE_FROM_TEMPLATE` and
      `ROLE_COMPONENTS_REGISTRY_TEMPLATE_CREATOR`, plus a note on the availability rule
- [x] 5.2 `docs/registry/deployment/keycloak-setup.md`:
  - [x] 5.2.1 the realm role `COMPONENTS_REGISTRY_TEMPLATE_CREATOR` to provision (role table, Step 1)
  - [x] 5.2.2 the default-role step: a new Option C — `COMPONENTS_REGISTRY_VIEWER` in the default
        role, `COMPONENTS_REGISTRY_EDITOR` and the template creator role by group, service accounts
        given the editor role directly, CRS deployed first; Step 3 checks a template-only creator
- [x] 5.3 `technical-design.md` §6.3 — the availability table; the create gate stays `CREATE_COMPONENTS`
- [x] 5.4 `functional-spec.md` §7.4 and §7.5 — who may use a template, and that only
      `CREATE_COMPONENTS` may send overrides
- [x] 5.5 `requirements-common.md` — SYS-104, summary row and section, with its tests; the new
      tests' `@DisplayName`s start with `SYS-104:`
- [x] 5.5a Renumbered to SYS-104 on merging main: SYS-102 was taken by the TeamCity placement
      Diff/Sync requirement and SYS-103 by the client retry policy, both merged first (added on review)
- [x] 5.6 `api-changelog.md` — the new permission and role, the reason in the listing, 403 on
      overrides for template-only creators
- [x] 5.7 `v4.json` — regenerated with `generateOpenApiDocs`; unchanged, since no schema changes

## 6. Finalization

- [x] 6.1 `test qualityStatic` across all modules (JDK 21; the shell default, JDK 25, cannot compile
      `components-registry-api` at source 8):
  - unit `test`: 2073 tests, 0 failures, 3 skipped
  - `ktlintCheck` and `detekt` clean after shortening five `@DisplayName`s over 140 characters and
    re-ordering imports (`ktlintTestSourceSetFormat`) (added on review)
  - the touched H2 suites under `dbTest`: `RoleMapTest` 3, `ComponentProfileControllerV4Test` 12,
    `ComponentTemplateControllerV4Test` 34, `ProfileOnCreateTest` 18,
    `ComponentOwnershipEditSecurityTest` 20 — 0 failures
  - no build file touched, so the coverage floors are unchanged
  - not green locally, and not caused by this change:
    - `:components-registry-automation` cannot resolve `kotlin-test:1.9.25`, as on main, and its
      `test` deploys to OKD — left to CI
    - `:components-registry-compat-test:test` excluded — left to CI
    - `:component-resolver-core:test` crashed once in the JIT compiler (SIGABRT, `replay_pid` log);
      re-run alone: 219 tests, 0 failures. No change in that module
    - the Postgres suites under `dbTest` (e.g. `UserInfoConverterIntegrationTest`) need Docker —
      left to CI
- [x] 6.2 Out-of-scope boundaries hold, checked by diff against `origin/main`:
  - [x] 6.2.1 no AD group name and no `GROUP_*` read in the code
  - [x] 6.2.2 no change to `canEditComponent`, rename, archive or delete
  - [x] 6.2.3 `ROLE_COMPONENTS_REGISTRY_EDITOR` and `ROLE_ADMIN` unchanged here (only the new role
        added to `application.yml`); service-config not touched
  - [x] 6.2.4 `POST /rest/api/4/components` guard unchanged — no controller in the diff
- [x] 6.3 Risks in `design.md` still stated
- [ ] 6.4 On archive, drop the line "Who counts as Delivery & Support is not covered here." from the
      `component-templates` Purpose: a delta cannot change a Purpose
