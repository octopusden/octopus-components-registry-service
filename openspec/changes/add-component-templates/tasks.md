## 1. Baseline

- [x] 1.1 Characterization tests on what this change alters, green on the unchanged code:
  - [x] 1.1.1 A reload with a template entry answers 200, the entry `failed` with "templates are
        not supported yet" (becomes `live` for a valid template in section 3) — already pinned by
        `ReloadConfigComponentProfilesTest.templateEntry`
  - [x] 1.1.2 A create whose `name` is taken throws `IllegalArgumentException`
        `name: a component with name '<key>' already exists` and leaves no row
  - [x] 1.1.3 A create rolled back after `saveAndFlush` leaves no component, label dictionary row
        or audit row (Decision 8 relies on it)
- [x] 1.2 Re-check Decision 6's table against the Portal's `initialValues`; adjust the table if the
      Portal changed
  - [x] 1.2.1 The line format falls back to the minor format, as the Portal sends it; table fixed
        (added on review)
- [x] 1.3 Required fields by classification written into the spec and Decision 4 (added on review)

## 2. Template model and parsing (Decisions 1–4)

- [x] 2.1 Write failing unit tests for `TemplateParser` and `TemplateExpression`:
  - [x] 2.1.1 The design example parses to a live template: four parameters, fourteen fields, two
        overridable paths
  - [x] 2.1.2 Required keys — each missing one → a problem naming it:
    - [x] `title`, `description`, `order`, `version`, `fields`
    - [x] `classification.external`, `classification.explicit`
    - [x] a parameter's `label`, `type`
  - [x] 2.1.3 Unknown keys — a problem naming the key, at each level:
    - [x] template
    - [x] classification; `explicit: ask` is also a problem
    - [x] parameter
    - [x] rule
  - [x] 2.1.4 Parameter checks, one test each:
    - [x] bad parameter name
    - [x] a key that does not apply to the type
    - [x] `multiple` on `text`, and on `crs-list`
    - [x] `select` without options; with a repeated option
    - [x] unknown `list`; `list: client-codes`
    - [x] pattern that does not compile
    - [x] `max-length` / `max-selection` not above 0
    - [x] invalid default: not an option, not matching the pattern, too long, several values for
          a single-value parameter
    - [x] unused parameter
  - [x] 2.1.5 Field checks, one test each:
    - [x] path outside the table
    - [x] undefined parameter
    - [x] Person in free text
    - [x] multi-value parameter in a non-list field
    - [x] CRS value, Person or list item with text around `{{ NAME }}`, or with a filter
    - [x] filter other than `lower` / `upper`
    - [x] malformed `{{`
    - [x] fixed build system or escrow mode not in its enum
    - [x] fixed choice outside its values
  - [x] 2.1.6 Whole-template checks, one test each:
    - [x] each required field, for explicit + external and not
    - [x] distribution: none of Maven GAV, Docker image, package → fails; `WHISKEY` → passes
    - [x] build system from a parameter → VCS fields required
    - [x] a required field filled by a fixed value, by a required parameter, by a component
          default → passes
    - [x] a required field only the Portal's fallback would fill → fails
    - [x] no VCS needed, for each of the five build systems
    - [x] `overridable` path not set in `fields`
    - [x] fixed value breaking a template rule
    - [x] `solution: true` without explicit and external
  - [x] 2.1.7 Fixed labels, fixed people and uniqueness are not checked on load
  - [x] 2.1.8 Several problems in one template → all reported, each naming its parameter or field
  - [x] 2.1.9 Expressions (Jinja subset):
    - [x] spaces inside the braces are optional
    - [x] literal text is kept
    - [x] `{{` without a closing match is rejected
    - [x] a `{% … %}` tag and a `{# … #}` comment are rejected
    - [x] an expression other than a parameter name (`{{ A ~ B }}`, `{{ 'x' }}`) is rejected
- [x] 2.2 Implement:
  - [x] 2.2.1 `template/ComponentTemplate.kt` — template, parameter (sealed by type), field value
  - [x] 2.2.2 `template/TemplateFields.kt` — path → field kind table (Decision 2)
  - [x] 2.2.3 `template/TemplateExpression.kt` — parse one value into literal and parameter parts
        (rendering moves to section 5)
  - [x] 2.2.4 `template/TemplateParser.kt` — pure; problems prefixed with the full key
  - [x] 2.2.5 `ComponentProfileParser` hands `kind: template` to `TemplateParser`; `ProfileLoad`
        gains `templates`
- [x] 2.3 Confirm tests pass: `./gradlew :components-registry-service-server:test` — 1355 tests,
      0 failures, 1 skipped (pre-existing); `TemplateExpressionTest` 15, `TemplateParserTest` 74.
      `dbTest` for `TemplateCreateBaselineTest` 2, `ReloadConfigComponentProfilesTest` 5,
      `ComponentProfileControllerV4Test` 9 — all green. ktlint and detekt clean.
- [x] 2.4 Split out of `TemplateParser` to keep each file to one job (added on review):
  - [x] 2.4.1 `template/EntryKeys.kt` — flattened keys of an entry or section, and its problem list
  - [x] 2.4.2 `template/TemplateParameterParser.kt` — one parameter and its checks
  - [x] 2.4.3 `template/TemplateFieldChecker.kt` — the field-kind checks
  - [x] 2.4.4 `template/TemplateRequiredFields.kt` — the required-fields table
  - [x] 2.4.5 `profile/FieldRuleParser.kt` — rule parsing shared by profiles and templates
- [x] 2.5 Profile tests that pinned "templates are not supported yet" now expect a broken template
      to fail with its own problems (added on review)
- [ ] 2.6 The catalog still parses with no `component-defaults`, so a template that relies on a
      default fails its load check until section 5 passes them in (added on review)

## 3. Catalog, listing and availability (Decisions 1, 12)

- [x] 3.1 Write failing tests:
  - [x] 3.1.1 Catalog swap:
    - [x] a usable load swaps profiles and templates together
    - [x] a load with an invalid regular profile keeps both, including a template whose change
          was valid
  - [x] 3.1.2 Catalog keeps the last load's outcome and each entry's raw keys
  - [x] 3.1.3 Reload adding a valid and a broken template → 200, `live` and `failed` (replaces
        1.1.1's expectation)
  - [x] 3.1.4 Startup with valid regular profiles and a broken template → starts, template absent
  - [x] 3.1.5 Listing:
    - [x] templates next to profiles, by `order` then id
    - [x] kind `template`, `version`, explicit `true` / `false`, rules
    - [x] a failed template absent
  - [x] 3.1.6 `PermissionProfileAvailability`:
    - [x] template usable with `CREATE_COMPONENTS`, unusable without
    - [x] `mayOverride` follows usability
  - [x] 3.1.7 A create naming a template id as `profile` is still 400 `profile: unknown profile`
- [x] 3.2 Implement:
  - [x] 3.2.1 Catalog snapshot of profiles and templates
  - [x] 3.2.2 `ProfileAvailability.evaluate(template)` and `mayOverride`
  - [x] 3.2.3 `ComponentProfileResponse.version` and the template mapping
- [x] 3.3 Confirm tests pass: `test` — `ComponentProfileCatalogTest` 14, `PermissionProfileAvailabilityTest` 4,
      `ProfileCreateCheckTest` 16; `dbTest` — `ComponentProfileControllerV4Test` 10,
      `ProfileOnCreateTest` 17, `ReloadConfigComponentProfilesTest` 5. ktlint and detekt clean.
- [x] 3.4 The catalog takes a `component-defaults` supplier, read on every load; tested with a
      template whose required branch only a default fills (added on review)

## 4. Parameter checks (Decision 5)

- [ ] 4.1 Write failing unit tests for `ParameterChecker`, with stub `ListValues` and employee
      lookups:
  - [ ] 4.1.1 P1–P9, one failing and one passing case each
  - [ ] 4.1.2 P5 uses the template's message when set, a default message otherwise
  - [ ] 4.1.3 P6 with client codes listed as `select` options: an unlisted code fails
  - [ ] 4.1.4 P7 for each list:
    - [ ] labels against the dictionary
    - [ ] build systems and escrow modes against their enums
  - [ ] 4.1.5 Absent parameter takes its default; `current-user` resolves to the caller
  - [ ] 4.1.6 Empty optional parameter passes; a duplicate value counts once
  - [ ] 4.1.7 Employee service unavailable → P8 passes
  - [ ] 4.1.8 Several failures → all reported
- [ ] 4.2 Implement:
  - [ ] 4.2.1 `template/ParameterChecker.kt`
  - [ ] 4.2.2 `template/ListValues.kt` and its implementation: labels dictionary, the two enums
- [ ] 4.3 Confirm tests pass

## 5. Rendering (Decision 6)

- [ ] 5.1 Write failing unit tests for `TemplateRenderer`:
  - [ ] 5.1.1 R1–R8, one test each, including sources per field
  - [ ] 5.1.2 The design example renders to the expected request and sources
  - [ ] 5.1.3 Same input twice → equal output
  - [ ] 5.1.4 Defaults (`ComponentDefaultsSeedTest`):
    - [ ] each row of Decision 6's table applied only when the field is unset and the default is
          non-blank, with no sources
    - [ ] copyright only for explicit + external
    - [ ] VCS tag and branch only when the build system needs VCS
    - [ ] minor falls back to line; build omitted when equal to release
    - [ ] deprecated build system ignored
    - [ ] no `master` or version-format fallback
  - [ ] 5.1.5 Classification, Jira task key and comment carried; `profile` absent
- [ ] 5.2 Implement `template/TemplateRenderer.kt` and `template/ComponentDefaultsSeed.kt`
- [ ] 5.3 The catalog passes `ComponentDefaultsSeed`'s paths to `ComponentProfileParser.parse`, on
      load and on reload, closing 2.6 (added on review)
- [ ] 5.4 Confirm tests pass

## 6. Describe parameters

- [ ] 6.1 Write failing tests for `GET /rest/api/4/component-templates/{id}`:
  - [ ] 6.1.1 Each type's settings and default
  - [ ] 6.1.2 `crs-list` current values
  - [ ] 6.1.3 `current-user` returned as the caller
  - [ ] 6.1.4 404 for a failed and for an unknown template
  - [ ] 6.1.5 403 without `ACCESS_COMPONENTS`
- [ ] 6.2 Implement `controller/ComponentTemplateControllerV4.kt` (database mode) and its DTOs
- [ ] 6.3 Confirm tests pass

## 7. Dry run (Decisions 7–9)

- [ ] 7.1 Write failing tests:
  - [ ] 7.1.1 `CreateFailureFields`:
    - [ ] one test per message prefix today's create emits, mapped to its paths
    - [ ] an unmatched message → no field
  - [ ] 7.1.2 Template rule failure attributed to its parameters; fixed field → template problem
  - [ ] 7.1.3 Through `POST …/components`, `dryRun` absent or `true`:
    - [ ] `dryRun` absent → a dry run: 200, nothing created
    - [ ] an invalid input still answers 200, `valid: false`
    - [ ] a malformed Jira task key → 400
    - [ ] parameter problems stop it
    - [ ] key taken → problem on `name` naming `CLIENT_CODE`, `PLUGIN_CODE`
    - [ ] rule and create problems both reported
    - [ ] unknown fixed label → template problem
    - [ ] inactive fixed owner → template problem
    - [ ] 404 for a failed template
  - [ ] 7.1.4 Nothing written, after a passing and after a failing dry run: no component, label,
        audit or TeamCity dictionary row
  - [ ] 7.1.5 A 409 cross-component conflict and a 403 editability failure become problems, not
        error responses
- [ ] 7.2 Implement:
  - [ ] 7.2.1 `template/CreateFailureFields.kt`
  - [ ] 7.2.2 `template/TemplateDryRun.kt` — rollback-only `TransactionTemplate` around
        `createComponent`
  - [ ] 7.2.3 `POST …/components` on `ComponentTemplateControllerV4` with `dryRun` (default
        `true`), and its DTOs
- [ ] 7.3 Confirm tests pass, `dbTest` included

## 8. Create from a template and overrides (Decision 10)

- [ ] 8.1 Write failing tests through `POST /rest/api/4/component-templates/{id}/components?dryRun=false`:
  - [ ] 8.1.1 Clean input → 201; the audit row has the Jira task key and comment; the component
        has no link to the template
  - [ ] 8.1.2 Any problem → 422 with the dry-run body, nothing created
  - [ ] 8.1.3 Malformed Jira task key → 400; blank or absent key → created
  - [ ] 8.1.4 Template removed by a reload → 404
  - [ ] 8.1.5 Overrides:
    - [ ] overridable path → created with the value
    - [ ] non-overridable path → 400 naming it
    - [ ] `mayOverride` false (stubbed) → 403
    - [ ] invalid override → problem on that field
  - [ ] 8.1.6 The same body sent without `dryRun`, then with `dryRun=false` → 200 valid, then 201
- [ ] 8.2 Implement `dryRun=false` on the same endpoint, and the committing path in
      `TemplateDryRun`
- [ ] 8.3 Confirm tests pass

## 9. Administrator read (Decision 11)

- [ ] 9.1 Write failing tests for `GET /rest/api/4/admin/component-profiles`:
  - [ ] 9.1.1 Live and failed entries, with definition, problems and configuration text
  - [ ] 9.1.2 Configuration version present, and absent
  - [ ] 9.1.3 Last reload failed → said so, with the problems
  - [ ] 9.1.4 403 without `IMPORT_DATA`
- [ ] 9.2 Implement on `AdminControllerV4`, with the YAML dump of an entry's raw keys
- [ ] 9.3 Confirm tests pass

## 10. Docs and contract

- [ ] 10.1 Regenerate `v4.json`; `OpenApiV4SpecTest` green
- [ ] 10.2 `docs/registry/api-changelog.md`:
  - [ ] the four endpoints
  - [ ] `kind: template` and `version` in the listing
  - [ ] `live` templates in the reload response
- [ ] 10.3 `docs/registry/functional-spec.md` — templates, parameters, dry run, create
- [ ] 10.4 ADR-016 — templates as entries of the `component-profiles` subtree; field keys are
      create-request paths
- [ ] 10.5 Tech-debt records, next free `TD-NNN`:
  - [ ] 10.5.1 The dry run reports one failure of today's create rules at a time
  - [ ] 10.5.2 No client-code list in the registry:
    - [ ] the limit: a template lists client codes by hand as `select` options, so a new client
          needs a template change
    - [ ] removal: read the list from the service that owns client codes, as a `client-codes`
          `crs-list`
- [ ] 10.5a TD-025 (Portal-only create rules): add the required Jira task key, which the Portal
      enforces and the registry does not
- [ ] 10.6 Listing DTO schema text: `kind` now includes `template`

## 11. Finalization

- [ ] 11.1 `test qualityStatic` across all modules green; coverage floors unchanged
- [ ] 11.2 Out-of-scope boundaries hold, checked by diff:
  - [ ] no D&S rule beyond the availability seam
  - [ ] no change to `createComponent`'s checks
  - [ ] no change to the update, import or field-override paths
  - [ ] no migration
- [ ] 11.3 Risks in `design.md` still stated:
  - [ ] the one-failure and client-code risks have their tech-debt records
  - [ ] the optional Jira task key is in TD-025
