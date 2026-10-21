## 1. Registry: baseline

- [x] 1.1 Characterization tests from `test/onb-001-baseline` merged into this branch, green

## 2. Registry: revision 2 parts that stand (implemented)

- [x] 2.1 `V8__` migration + entity columns; migration test on Testcontainers
- [x] 2.2 DSL import back-fill after the first entry of multi-entry rows
- [x] 2.3 v4 request/response entry fields and round-trip; blank = absent
- [x] 2.4 `vcsEntries[<i>].<field>: ` errors with the `fieldOverrides[<j>].` prefix in a combined
      PATCH; segment regex, fixed reserved names ignoring case, 255-character limit
- [x] 2.5 Chain-mismatch warning and `warnings` on the detail response
- [x] 2.6 Groovy model + `VersionControlSystemRootDTO` fields (appended; explicit six-parameter
      constructor with `@JsonCreator(mode = DISABLED)`); v2 exposure; Groovy six-argument call test
- [x] 2.7 DSL export omits the entry fields; git-vs-db and compat known differences

## 3. Registry: revision 3 (#503, released in 3.0.14; test-first; each step starts with a failing test)

- [x] 3.1 Checkout Directory optional on every entry, single-entry rows included; at most one entry
      without it (second one → `vcsEntries[<i>].checkoutDirectory`); revision 2's "must be empty on
      the primary" and "required on a secondary" rules removed
- [x] 3.2 Name rule: Checkout Directory, else the previous name of the same repository, else
      `main`; unique ignoring case, reported on the later entry
- [x] 3.3 `V9__add_build_working_directory.sql` + entity column; migration test on Testcontainers
      (V8 and V9 from an empty database and from a V8 database)
- [x] 3.4 v4 `buildWorkingDirectory` on the base request/response and the `vcs.settings` marker
      payload (`rejectExtraneousMarkerFields`), blank = absent, round-trip
- [x] 3.5 Build Working Directory validation (shape, 255, inside a placed entry, required when
      every entry has a Checkout Directory), also when only it changes; `buildWorkingDirectory: ` errors, prefixed `fieldOverrides[<j>].` in a combined PATCH
- [x] 3.6 Groovy `VCSSettings` (incl. `equals`/`hashCode`/`toString`) +
      `VCSSettingsDTO.buildWorkingDirectory` (appended, property-level
      NON_NULL, explicit two-parameter constructor with `@JsonCreator(mode = DISABLED)`); DB mapper
      and v2 controller; v2 byte identity when unset; Groovy two-argument call test
- [x] 3.7 Chain-mismatch warning also on a base `buildWorkingDirectory`
- [x] 3.8 DSL export omits `buildWorkingDirectory`; git-vs-db known difference; compat known-delta
      widened to every root (`\[\d+\]`, typed comparator at any index) plus
      `buildWorkingDirectory`; `api-compat-deltas.md` VCS checkout layout entry rewritten for revision 3
- [x] 3.9 `api-changelog.md`, `functional-spec.md`, `schema-spec.md` updated; rollback runbook in
      `docs/registry/deployment/vcs-checkout-layout-rollback.md` rewritten for revision 3 (restore from
      snapshot by (configuration, repository, `sort_order`), not V8's rule); `V8__`'s SQL comment
      ("NULL on the primary entry") is immutable, so `schema-spec.md` and `functional-spec.md`
      carry the revision 3 wording; comment on PR #481 that it becomes `V10__`
- [x] 3.10 Local build (documented command; `docker*`, `oc*` and
      `:components-registry-automation:test` excluded, coverage gap stated in the report),
      `dbTest` and `:integrationTest` green; QA redeploy

## 4. Checkout layout import: Diff/Sync jobs (#510, released in 3.1.1; renamed in #530)

- [x] 4.1 Diff job on the `MigrationLifecycleGate` mutex, polling and the Events feed: the
      derivation rules of ADR-023 decision 3 (full-URL matching including host; a repository
      attached twice with different rules is CONFLICT), the status vocabulary of
      `teamcity-checkout-layout-sync`, the registry-validation gate for RESOLVED vs INVALID; base
      rows of non-archived components only
- [x] 4.2 Each run has an id; HTML, CSV and JSON for the last completed result, kept in memory
- [x] 4.3 ROOTS_MISMATCH, and the same comparison as the TeamCity validation warning
      `VCS_ROOTS_DIFFER_FROM_REGISTRY`
- [x] 4.4 Sync job: `{diffId, componentIds}`, only RESOLVED components, through the v4 write path
      with the `sync from TeamCity (job <id>)` comment; stale `diffId` → 409
      `checkout-layout-diff-stale`; provenance rule; "changed since diff" guard; the reserved
      comment prefix rejected on user writes (400)
- [x] 4.5 Before/after CSV per written field; rollback procedure in technical-design §6.10
- [x] 4.6 Endpoints under `/rest/api/4/admin/teamcity-checkout-layout`; the first name,
      `/teamcity-placement`, kept as deprecated aliases
- [x] 4.7 Compile configurations recognised by the configured template ids
      (`teamcity.validation.{gradle,maven}-build-template-id`), not hardcoded ones

## 5. Portal (octopus-components-management-portal)

- [x] 5.1 VCS tab and per-range editor: Source Path, Checkout Directory, Build Working Directory;
      `name` read-only; field errors routed to the field; the chain-mismatch warning (#237,
      folded into #238)
- [x] 5.2 Admin → Maintenance card: Run Diff, the report (HTML, CSV), selection of ready
      components, Sync with the rollback CSV (#238)
- [ ] 5.3 Follow the "checkout layout" rename (new endpoints, error code, card title)

## 6. Build-chain generator (octopus-teamcity-automation)

- [x] 6.1 Characterization tests of the single-root behaviour, negative-checked
- [x] 6.2 One TeamCity VCS root per registry root with the checkout rule of its layout; attach
      order; default branch from the registry; `WORK_DIR`/`COMPONENT_CONFIG_DIR`; unsupported
      shapes fail before creation (#78, released in 1.1.0)
- [x] 6.3 `BUILD_VERSION_FORMAT_FILE` on created configurations with a *Calculate Build Version*
      step when a Build Working Directory is set (ADR-022 decision 9)
- [ ] 6.4 Compile template ids default to the original `CDCompileUTGradle`/`CDCompileUTMaven`
      again (#78 had switched them to one installation's renamed ids), with
      `--gradle-compile-template` / `--maven-compile-template` (#85)

## 7. Sonar automation (octopus-sonar-automation)

- [x] 7.1 Target-branch fetch in `WORK_DIR` (#24, released in 2.0.19)

## 8. End to end

- [ ] 8.1 The scenarios of `multi-vcs-root-build-chain` on a TeamCity test project, after the
      template change of ADR-022 decision 9
- [ ] 8.2 Diff, Sync of a few components, Diff again showing them in sync
