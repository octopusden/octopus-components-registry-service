## 1. Baseline

- [ ] 1.1 Characterization tests on what this change alters, green on the unchanged code:
  - [ ] 1.1.1 A user with `ROLE_COMPONENTS_REGISTRY_EDITOR` gets every profile and template usable
        in the listing, and may override a template field
  - [ ] 1.1.2 A user with `ROLE_COMPONENTS_REGISTRY_VIEWER` gets every entry unusable with "You do
        not have permission to create components", and 403 on a template dry run
  - [ ] 1.1.3 `POST /rest/api/4/components` answers 403 to a viewer

## 2. Permission and role (Decision 1)

- [ ] 2.1 Write failing tests:
  - [ ] 2.1.1 `GET /auth/me` with `ROLE_COMPONENTS_REGISTRY_TEMPLATE_CREATOR` lists
        `ACCESS_COMPONENTS`, `ACCESS_AUDIT` and `CREATE_FROM_TEMPLATE` for that role, read from the
        bundled role map
  - [ ] 2.1.2 The same role leaves `ROLE_COMPONENTS_REGISTRY_EDITOR` and `ROLE_ADMIN` unchanged
- [ ] 2.2 Implement:
  - [ ] 2.2.1 `PermissionEvaluator` — `CREATE_FROM_TEMPLATE` in the companion, beside the others
  - [ ] 2.2.2 bundled `application.yml` — `ROLE_COMPONENTS_REGISTRY_TEMPLATE_CREATOR`
  - [ ] 2.2.3 `SecurityTestHelpers` — `templateCreatorJwt()`
- [ ] 2.3 Confirm tests pass.

## 3. Availability (Decisions 2, 3)

- [ ] 3.1 Write failing unit tests for `PermissionProfileAvailability`:
  - [ ] 3.1.1 `CREATE_COMPONENTS`: profile usable, template usable, override allowed
  - [ ] 3.1.2 `CREATE_FROM_TEMPLATE` only: profile unusable with "You may create components
        from templates only", template usable, override refused
  - [ ] 3.1.3 Both: profile usable, template usable, override allowed
  - [ ] 3.1.4 Neither: profile and template unusable with "You do not have permission to create
        components", override refused
- [ ] 3.2 Implement:
  - [ ] 3.2.1 `PermissionProfileAvailability` — the table in Decision 3, `CREATE_COMPONENTS` first;
        permission names from `PermissionEvaluator`
  - [ ] 3.2.2 KDoc on `ProfileAvailability` — drop "a later restriction" now that it exists
- [ ] 3.3 Confirm tests pass.

## 4. Endpoints with a template-only creator (Decisions 4, 5)

- [ ] 4.1 Write failing controller tests with `templateCreatorJwt()`:
  - [ ] 4.1.1 Listing: templates usable, regular profiles unusable with the template-only reason
  - [ ] 4.1.2 Template dry run without overrides: 200
  - [ ] 4.1.3 Template create without overrides: 201, component created
  - [ ] 4.1.4 Template dry run with an overridable override: 403, nothing created
  - [ ] 4.1.5 Template dry run with a non-overridable override: 403, not 400 (order 5 before 6)
  - [ ] 4.1.6 `POST /rest/api/4/components` without a profile: 403, nothing created
  - [ ] 4.1.7 `POST /rest/api/4/components` naming a regular profile: 403, nothing created
  - [ ] 4.1.8 `PATCH` of a component the user owns: allowed
- [ ] 4.2 Implement: none expected beyond section 3. Any controller change found here is recorded
      as a task `(added on review)`.
- [ ] 4.3 Confirm tests pass.

## 5. Docs

- [ ] 5.1 ADR-004 — the permission table and the role map gain `CREATE_FROM_TEMPLATE` and
      `ROLE_COMPONENTS_REGISTRY_TEMPLATE_CREATOR`
- [ ] 5.2 `docs/deployment/keycloak-setup.md` — the realm role `COMPONENTS_REGISTRY_TEMPLATE_CREATOR`
      to provision
- [ ] 5.3 `technical-design.md` — the availability table; the create gate stays `CREATE_COMPONENTS`
- [ ] 5.4 `functional-spec.md` — who may create from a template, and who may override
- [ ] 5.5 `requirements-common.md` — next free `SYS-NNN` for template-only creators, with its
      tests
- [ ] 5.6 `api-changelog.md` — the new reason in the listing; 403 on overrides for template-only
      creators
- [ ] 5.7 `v4.json` — regenerate; `OpenApiV4SpecTest` green. Expected unchanged, since no schema
      changes

## 6. Finalization

- [ ] 6.1 `test qualityStatic` across all modules green; coverage floors unchanged
- [ ] 6.2 Out-of-scope boundaries hold, checked by diff:
  - [ ] 6.2.1 no AD group name and no `GROUP_*` read in the code
  - [ ] 6.2.2 no change to `canEditComponent`, rename, archive or delete
  - [ ] 6.2.3 `ROLE_COMPONENTS_REGISTRY_EDITOR` and `ROLE_ADMIN` unchanged, here and in service-config
  - [ ] 6.2.4 `POST /rest/api/4/components` guard unchanged
- [ ] 6.3 Risks in `design.md` still stated
