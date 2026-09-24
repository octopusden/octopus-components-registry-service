## 1. Baseline

- [ ] 1.1 Characterization tests from `test/onb-001-baseline` merged into this branch, green

## 2. Implementation (test-first; each step starts with a failing test)

- [ ] 2.1 Migration + entity columns; migration test on Testcontainers (single- and multi-entry rows)
- [ ] 2.2 v4 request/response fields and round-trip
- [ ] 2.3 Validation rules with field-prefixed errors; reserved names incl. configuration property
- [ ] 2.4 Derived name
- [ ] 2.5 Chain-mismatch warning and `warnings` on the detail response
- [ ] 2.6 Groovy model + v2 DTO fields (appended, six-parameter constructor kept); v2 exposure
- [ ] 2.7 DSL export omits fields; git-vs-db known difference
- [ ] 2.8 `api-changelog.md`, `functional-spec.md`, `schema-spec.md` updated
- [ ] 2.9 Full local build (documented command) green
