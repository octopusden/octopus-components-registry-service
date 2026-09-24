## 1. Baseline

- [ ] 1.1 Characterization tests from `test/onb-001-baseline` merged into this branch, green

## 2. Implementation (test-first; each step starts with a failing test)

- [ ] 2.1 `V8__` migration + entity columns; migration test on Testcontainers (single- and
      multi-entry rows, a row with duplicate names, a row with an invalid name)
- [ ] 2.2 DSL import back-fill for multi-entry rows
- [ ] 2.3 v4 request/response fields and round-trip
- [ ] 2.4 Validation rules in `replaceVcsEntries` with `vcsEntries[<i>].<field>: ` errors (base and
      marker rows); fixed reserved names
- [ ] 2.5 Derived name (Checkout Directory; sole previous name; `main`), incl. 2→1 and re-pointed
- [ ] 2.6 Chain-mismatch warning and `warnings` on the detail response
- [ ] 2.7 Groovy model + v2 DTO fields (appended, `@JvmOverloads`); v2 exposure; Groovy
      six-argument call test
- [ ] 2.8 DSL export omits fields; git-vs-db known difference
- [ ] 2.9 `api-changelog.md`, `functional-spec.md`, `schema-spec.md` updated; rollback repair
      runbook in `docs/registry/deployment/`
- [ ] 2.10 Full local build (documented command) green
