# Project Guidance

## CI Workflows
- Reuse shared GitHub workflows from `octopus-base` for `build`, `quality`, and `security` instead of copying repository-local workflow logic.
- For PR checks, avoid duplicate runs: prefer `pull_request` and restrict `push` triggers to `main` or release branches.

## Quality Gates
- Keep repository-specific rule configuration, baselines, thresholds, and source-set wiring in this repository.
- Java checks (`Checkstyle`, `PMD`) are blocking.
- Kotlin checks (`detekt`, `ktlint`) are blocking and use module-level baselines.
- Groovy `CodeNarc` is currently report-only; do not turn it into a blocking gate without an explicit rollout decision.

## Coverage
- Treat unit-test coverage and FT/integration coverage as separate concerns.
- Generic GitHub quality checks should enforce only coverage that GitHub actually measures reliably.
- FT or OKD coverage should be enforced only in the environment that really runs those scenarios.
- The repository uses both a low per-module coverage floor and an overall weighted coverage threshold.

## Reports
- Publish machine-readable reports for CI and keep `build/reports/quality/index.html` as the unified human-readable entry point when multiple reports exist.
- If the unified index should be available from a normal `build`, wire it to `build` or `check` and ensure it depends on the report-producing tasks.
- TeamCity XML watcher paths and artifact paths are separate concerns; configure both explicitly.
