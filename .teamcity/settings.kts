import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildFeatures.XmlReport
import jetbrains.buildServer.configs.kotlin.buildFeatures.dockerSupport
import jetbrains.buildServer.configs.kotlin.buildFeatures.swabra
import jetbrains.buildServer.configs.kotlin.buildFeatures.xmlReport
import jetbrains.buildServer.configs.kotlin.buildSteps.gradle
import jetbrains.buildServer.configs.kotlin.buildSteps.kotlinFile
import jetbrains.buildServer.configs.kotlin.buildSteps.script
import jetbrains.buildServer.configs.kotlin.triggers.finishBuildTrigger
import jetbrains.buildServer.configs.kotlin.vcs.GitVcsRoot

version = "2025.03"

project {
    description = "https://github.com/octopusden/octopus-components-registry-service"

    vcsRoot(ComponentsRegistry)
    vcsRoot(CrsCompatTrace)
    vcsRoot(ServiceConfig)

    params {
        param("JDK_VERSION", "11")
        param("LAST_RELEASE_VERSION", "2.0.87")
        param("PROJECT_VERSION", "")
        param("COMPONENTS_REGISTRY_BRANCH", "master")
        param("OCTOPUS_MODULE_NAME", "octopus-components-registry-service")
        param("RELENG_SKIP", "true")
        param("OKD_IMAGE_NAME", "components-registry-service")
        param("env.JAVA_HOME", "%env.JDK_21_0_x64%")
        param("COMPONENTS_REGISTRY_CHECKOUT_DIR", "Components-Registry")
        param("COMPONENT_NAME", "components-registry-service")
        // Bitbucket repo URLs are CRS-specific (only used by this project's
        // VCS roots), so they live in the DSL. The bitbucket hostname itself
        // is shared across many TC projects and stays on the TC server as
        // %GIT_SERVER_HOSTNAME%; substitution at runtime keeps the host
        // out of the open-source repo while letting the slugs be readable.
        param("COMPONENTS_REGISTRY_REPO_URL", "ssh://git@%GIT_SERVER_HOSTNAME%/CREG/components-registry.git")
        param("CRS_COMPAT_TRACE_REPO_URL", "ssh://git@%GIT_SERVER_HOSTNAME%/CREG/crs-compat-trace.git")
    }

    // NOTE: id17 (db-mode) and id18 (git-mode) are both local-stand compat builds
    // that boot stands on fixed ports (4567/4568) + a local Postgres compose, but
    // they run on SEPARATE agents — each with its own localhost and Docker — so
    // they cannot collide and need no cross-build lock. (An earlier Shared
    // Resources write-lock was removed: unnecessary given separate agents, and
    // unsupported by this server's kotlin-dsl distribution.)

    buildType(id10CompileUtAuto)
    buildType(id12IntegrationDbTestsAuto)
    buildType(id15CompatManual)
    buildType(id16CompatTraceReplayManual)
    buildType(id17CompatLocalStandManual)
    buildType(id18CompatLocalStandGitModeAuto)
    buildType(id20ValidateComponentsRegistryProductionDataAuto)
    buildType(id30DeployToOkdQaDevAuto)
    buildType(id31MigrateOnQaDevAuto)
    buildType(id40ReleaseManual)
    buildType(id50ReleasePostProcessingAuto)
    buildType(id60DeployToOkdQaGhAuto)
    buildType(id70DeployToOkdProdManual_2)
    buildType(WL_Validation_id)

    buildTypesOrder = arrayListOf(
        id10CompileUtAuto,
        id12IntegrationDbTestsAuto,
        id15CompatManual,
        id16CompatTraceReplayManual,
        id17CompatLocalStandManual,
        id18CompatLocalStandGitModeAuto,
        id20ValidateComponentsRegistryProductionDataAuto,
        id30DeployToOkdQaDevAuto,
        id31MigrateOnQaDevAuto,
        id40ReleaseManual,
        id50ReleasePostProcessingAuto,
        id60DeployToOkdQaGhAuto,
        id70DeployToOkdProdManual_2,
        WL_Validation_id
    )
}

// VCS root used by id20ValidateComponentsRegistryProductionDataAuto to check
// out the downstream Components-Registry config under
// %COMPONENTS_REGISTRY_CHECKOUT_DIR%. URL is held in %COMPONENTS_REGISTRY_REPO_URL%
// (TC server / parent-project param) — the actual bitbucket SSH URL is
// kept off-repo because it carries internal identifiers.
object ComponentsRegistry : GitVcsRoot({
    name = "Components_Registry"
    url = "%COMPONENTS_REGISTRY_REPO_URL%"
    branch = "%COMPONENTS_REGISTRY_BRANCH%"
    branchSpec = "+:%COMPONENTS_REGISTRY_BRANCH%"
    checkoutSubmodules = GitVcsRoot.CheckoutSubmodules.IGNORE
    authMethod = defaultPrivateKey {
        userName = "git"
    }
})

// VCS root used by id16CompatTraceReplayManual to check out the internal
// trace-data repo (deduplicated `<count>\t<METHOD>\t<path>` tables, refreshed
// from prod access-log captures). Held off-repo as a separate Bitbucket repo
// because the trace files contain real component names; the URL is kept in
// the TC server / parent-project param `CRS_COMPAT_TRACE_REPO_URL` so the
// open-source DSL stays free of internal identifiers.
object CrsCompatTrace : GitVcsRoot({
    name = "Crs_Compat_Trace"
    url = "%CRS_COMPAT_TRACE_REPO_URL%"
    branch = "refs/heads/master"
    branchSpec = "+:refs/heads/master"
    checkoutSubmodules = GitVcsRoot.CheckoutSubmodules.IGNORE
    authMethod = defaultPrivateKey {
        userName = "git"
    }
})

// VCS root used by id17CompatLocalStandManual to check out the internal
// service-config repository (per-environment YAML overlays consumed by
// the CRS server). URL kept in the TC server / parent-project param
// `SERVICE_CONFIG_REPO_URL` so the open-source DSL stays free of internal
// identifiers, same pattern as `ComponentsRegistry`.
object ServiceConfig : GitVcsRoot({
    name = "Service_Config"
    url = "%SERVICE_CONFIG_REPO_URL%"
    branch = "refs/heads/master"
    branchSpec = "+:refs/heads/master"
    checkoutSubmodules = GitVcsRoot.CheckoutSubmodules.IGNORE
    authMethod = defaultPrivateKey {
        userName = "git"
    }
})

object id10CompileUtAuto : BuildType({
    templates(AbsoluteId("Octopus_OctopusGradleBuild"))
    id("10CompileUtAuto")
    name = "[1.0] Compile & UT [AUTO]"

    artifactRules = """
        **/*.log => logs.zip
        %ARTIFACT_PATH%
        **/reports
        **/test-results
    """.trimIndent()

    params {
        param("ARTIFACT_PATH", """      **/build/reports/** => reports
      **/build/test-results/**/*.xml => test-results
      build/**/logs/** => logs
      build/**/diagnostics/** => diagnostics""")
        param("GRADLE_EXTRA_PARAMETERS", """
            -Pokd.cluster-domain=%OKD_APPS_DOMAIN_DEV%
            -Pokd.project=%OKD_F1_TEST_PROJECT%
        """.trimIndent())
        // [1.0] compiles, runs unit + H2/MOCK smoke + static quality, publishes artifacts and
        // pushes the image — one step, so the image carries [1.0]'s OWN version (no cross-config
        // propagation). `build` also runs the fat-jar FT (dockerPushImage depends on it, so it
        // gates the push) and :…-automation:test (depends on ocCreate -> dockerPushImage, so it
        // deploys the just-pushed image). Only the heavy @Tag("integration") DB suite is split
        // out, to [1.2] (excluded from build/check by the gradle tag filter).
        param("GRADLE_TASK", "clean build publish dockerPushImage")
        param("COMPONENTS_REGISTRY_BRANCH", "master")
    }

    steps {
        script {
            name = "Login to the OKD cluster"
            id = "Login_to_the_OKD_cluster"
            scriptContent = """
                oc login --token=%OKD_F1_FT_TOKEN% %OKD_SERVER_DEV_URL%
                oc project %OKD_F1_TEST_PROJECT%
            """.trimIndent()
            param("org.jfrog.artifactory.selectedDeployableServer.useSpecs", "false")
            param("org.jfrog.artifactory.selectedDeployableServer.uploadSpecSource", "Job configuration")
            param("org.jfrog.artifactory.selectedDeployableServer.downloadSpecSource", "Job configuration")
        }
        gradle {
            name = "Gradle Build & UT"
            id = "RUNNER_1768"
            tasks = "%GRADLE_TASK%"
            workingDir = "%WORK_DIR%"
            gradleParams = """
                --info
                %GRADLE_STANDARD_PARAMETERS%
                %GRADLE_EXTRA_PARAMETERS%
            """.trimIndent()
            enableStacktrace = true
            jdkHome = "%env.JAVA_HOME%"
            jvmArgs = "%JDK_CMDLINE_PARAMETERS%"
            dockerRunParameters = "--userns=keep-id -e JAVA_HOME=/opt/java/openjdk -v %env.BUILD_ENV%:/opt/BUILD_ENV -v %teamcity.build.checkoutDir%:/home/tcagent/work -v %teamcity.agent.jvm.user.home%:/home/tcagent -w /home/tcagent/work -e TZ=Europe/Brussels"
            param("org.jfrog.artifactory.selectedDeployableServer.defaultModuleVersionConfiguration", "GLOBAL")
        }
        stepsOrder = arrayListOf("Login_to_the_OKD_cluster", "RUNNER_1720", "RUNNER_1768")
    }

    failureConditions {
        executionTimeoutMin = 240
    }

    features {
        xmlReport {
            id = "BUILD_EXT_1736"
            reportType = XmlReport.XmlReportType.PMD
            rules = "+:**/build/reports/pmd/*.xml"
        }
        xmlReport {
            id = "BUILD_EXT_1793"
            reportType = XmlReport.XmlReportType.FINDBUGS
            rules = "**/findbugsXml.xml"
        }
        xmlReport {
            id = "BUILD_EXT_1804"
            reportType = XmlReport.XmlReportType.PMD_CPD
            rules = "**/cpd.xml"
        }
        xmlReport {
            id = "BUILD_EXT_1814"
            reportType = XmlReport.XmlReportType.FINDBUGS
            rules = "+:**/build/reports/spotbugs/*.xml"
        }
        xmlReport {
            id = "BUILD_EXT_1815"
            reportType = XmlReport.XmlReportType.JUNIT
            rules = """
                +:**/build/test-results/integrationTest/*.xml
                +:**/build/test-results/test/*.xml
            """.trimIndent()
        }
        xmlReport {
            id = "BUILD_EXT_1817"
            reportType = XmlReport.XmlReportType.CHECKSTYLE
            rules = "+:**/build/reports/checkstyle/*.xml"
        }
    }

    requirements {
        doesNotContain("env.OS_TYPE", "WIN", "RQ_2875")
    }
})

// [1.2] Integration & DB Tests — runs the heavy @Tag("integration") DB suite (Postgres
// Testcontainers / full Spring context, across server/client/light-client), triggered off
// id10 alongside the compat/validate chain. This is the ONLY thing split out of [1.0]
// (tagged out of `build`/`check`); it gates the deploy via id30's snapshot below.
// automation:test runs in [1.0] (it transitively triggers dockerPushImage), NOT here.
object id12IntegrationDbTestsAuto : BuildType({
    templates(AbsoluteId("Octopus_OctopusGradleBuild"))
    id("12IntegrationDbTestsAuto")
    name = "[1.2] Integration & DB Tests [AUTO]"

    buildNumberPattern = "%BUILD_NUMBER%"

    artifactRules = """
        **/*.log => logs.zip
        %ARTIFACT_PATH%
        **/reports
        **/test-results
    """.trimIndent()

    params {
        param("ARTIFACT_PATH", """      **/build/reports/** => reports
      **/build/test-results/**/*.xml => test-results
      build/**/logs/** => logs
      build/**/diagnostics/** => diagnostics""")
        param("GRADLE_EXTRA_PARAMETERS", """
            -Pokd.cluster-domain=%OKD_APPS_DOMAIN_DEV%
            -Pokd.project=%OKD_F1_TEST_PROJECT%
        """.trimIndent())
        // Pure DB/Spring heavy suite only. automation:test is intentionally NOT here: its test
        // task depends on ocCreate -> dockerPushImage, so running it would push the image; it
        // belongs in [1.0] (where `build`'s subproject sweep already runs it and the push
        // happens). dbTest itself pulls no docker/publish/ocCreate task.
        param("GRADLE_TASK", "clean :components-registry-service-server:dbTest :components-registry-service-client:dbTest :components-registry-service-light-client:dbTest")
        param("COMPONENTS_REGISTRY_BRANCH", "master")
        param("BUILD_NUMBER", "${id10CompileUtAuto.depParamRefs.buildNumber}")
    }

    steps {
        script {
            name = "Login to the OKD cluster"
            id = "Login_to_the_OKD_cluster"
            scriptContent = """
                oc login --token=%OKD_F1_FT_TOKEN% %OKD_SERVER_DEV_URL%
                oc project %OKD_F1_TEST_PROJECT%
            """.trimIndent()
            param("org.jfrog.artifactory.selectedDeployableServer.useSpecs", "false")
            param("org.jfrog.artifactory.selectedDeployableServer.uploadSpecSource", "Job configuration")
            param("org.jfrog.artifactory.selectedDeployableServer.downloadSpecSource", "Job configuration")
        }
        gradle {
            name = "Gradle Integration & DB Tests"
            id = "RUNNER_1768"
            tasks = "%GRADLE_TASK%"
            workingDir = "%WORK_DIR%"
            gradleParams = """
                --info
                %GRADLE_STANDARD_PARAMETERS%
                %GRADLE_EXTRA_PARAMETERS%
            """.trimIndent()
            enableStacktrace = true
            jdkHome = "%env.JAVA_HOME%"
            jvmArgs = "%JDK_CMDLINE_PARAMETERS%"
            dockerRunParameters = "--userns=keep-id -e JAVA_HOME=/opt/java/openjdk -v %env.BUILD_ENV%:/opt/BUILD_ENV -v %teamcity.build.checkoutDir%:/home/tcagent/work -v %teamcity.agent.jvm.user.home%:/home/tcagent -w /home/tcagent/work -e TZ=Europe/Brussels"
            param("org.jfrog.artifactory.selectedDeployableServer.defaultModuleVersionConfiguration", "GLOBAL")
        }
        stepsOrder = arrayListOf("Login_to_the_OKD_cluster", "RUNNER_1720", "RUNNER_1768")
    }

    failureConditions {
        executionTimeoutMin = 240
    }

    features {
        xmlReport {
            id = "BUILD_EXT_1815"
            reportType = XmlReport.XmlReportType.JUNIT
            rules = "+:**/build/test-results/dbTest/*.xml"
        }
    }

    requirements {
        doesNotContain("env.OS_TYPE", "WIN", "RQ_2875")
    }

    triggers {
        finishBuildTrigger {
            buildType = "${id10CompileUtAuto.id}"
            successfulOnly = true
            branchFilter = "+:*"
        }
    }

    dependencies {
        snapshot(id10CompileUtAuto) {
            onDependencyFailure = FailureAction.FAIL_TO_START
        }
    }
})

// Compat — HTTP (two pre-deployed URLs) — manual, on-demand. Runs the compat-
// test module against a baseline (production / main) and candidate (v3 stand)
// deployment pair via HTTP URLs (no JAR launch on the agent).
//
// Modes (all opt-in via prompt params, default smoke):
//   - default: smoke list (COMPAT_SMOKE_COMPONENTS, ~5 components) → ~1-3 min.
//   - COMPAT_FULL=true: full endpoint matrix (every component × every
//     compat-test class) → ~10-30 min.
//   - COMPAT_ALLOW_NON_DB_CANDIDATE=true: documented escape hatch when the
//     candidate intentionally serves V1 (parity-debug runs). Suppresses the
//     CANDIDATE_NOT_DB_MODE env-warning that would otherwise fail the build
//     even though all endpoint diffs are clean.
//
// Prompted on every run:
//   - 4 required (no defaults — `allowEmpty = false` rejects blanks):
//     COMPAT_BASELINE_URL, COMPAT_CANDIDATE_URL, COMPAT_RMS_URL,
//     COMPAT_SMOKE_COMPONENTS. Without them the task either skips silently
//     (smoke list empty) or reports only env preconditions, producing
//     misleading green builds.
//   - 2 boolean flags with `"false"` defaults (COMPAT_FULL,
//     COMPAT_ALLOW_NON_DB_CANDIDATE) — the operator only flips them when
//     they want the corresponding mode (full sweep / parity-debug).
object id15CompatManual : BuildType({
    templates(AbsoluteId("Octopus_OctopusGradleBuild"))
    id("15CompatManual")
    name = "[1.5] Compat — HTTP (two pre-deployed URLs) [MANUAL]"

    artifactRules = """
        %ARTIFACT_PATH%
    """.trimIndent()

    params {
        text("COMPAT_BASELINE_URL", "", allowEmpty = false, display = ParameterDisplay.PROMPT)
        text("COMPAT_CANDIDATE_URL", "", allowEmpty = false, display = ParameterDisplay.PROMPT)
        text("COMPAT_RMS_URL", "", allowEmpty = false, display = ParameterDisplay.PROMPT)
        text("COMPAT_SMOKE_COMPONENTS", "", allowEmpty = false, display = ParameterDisplay.PROMPT)
        text("COMPAT_FULL", "false", allowEmpty = false, display = ParameterDisplay.PROMPT)
        text("COMPAT_ALLOW_NON_DB_CANDIDATE", "false", allowEmpty = false, display = ParameterDisplay.PROMPT)
        param("ARTIFACT_PATH", """
            components-registry-compat-test/build/reports/compat/** => reports/compat
            components-registry-compat-test/build/test-results/**/*.xml => test-results/compat
        """.trimIndent())
        // `:test` already has finalizedBy 'compatibilityReporter' wired in
        // components-registry-compat-test/build.gradle, so the reporter
        // would run anyway. Listed explicitly so a TC operator reading
        // this config sees the full intent without cross-referencing
        // Gradle.
        param("GRADLE_TASK", ":components-registry-compat-test:test :components-registry-compat-test:compatibilityReporter")
        param("GRADLE_EXTRA_PARAMETERS", """
            -Pcompat.baseline.url=%COMPAT_BASELINE_URL%
            -Pcompat.candidate.url=%COMPAT_CANDIDATE_URL%
            -Pcompat.rms.url=%COMPAT_RMS_URL%
            -Pcompat.smoke-components=%COMPAT_SMOKE_COMPONENTS%
            -Pcompat.full=%COMPAT_FULL%
            -Pcompat.allow-non-db-candidate=%COMPAT_ALLOW_NON_DB_CANDIDATE%
        """.trimIndent())
    }

    steps {
        // Preflight guard: id15 lives only in v3-family branches that carry the
        // `components-registry-compat-test` module. If the operator triggers id15
        // from `main` (the legacy V1 trunk), the module is absent and the template
        // Gradle step would fail with "Project ':components-registry-compat-test'
        // not found". Catch that case here: rewrite `GRADLE_TASK` to a Gradle
        // no-op so the build passes green with a clear WARNING explaining why.
        //
        // The TC service message `setParameter` updates the build-level param at
        // runtime — the subsequent template-provided Gradle runner then expands
        // `%GRADLE_TASK%` to `help`, which is a Gradle built-in that prints task
        // listing and exits 0.
        script {
            name = "Preflight: skip-if-no-compat-test-module"
            id = "PRECHECK_COMPAT_MODULE"
            scriptContent = """
                #!/usr/bin/env bash
                set -eu
                if [ ! -d components-registry-compat-test ]; then
                  echo "::: components-registry-compat-test module not present in this checkout."
                  echo "::: id15 is only meaningful on v3-family branches that carry the compat-test infra."
                  echo "::: Rewriting GRADLE_TASK to 'help' so the template's Gradle step is a green no-op."
                  echo "##teamcity[setParameter name='GRADLE_TASK' value='help']"
                  echo "##teamcity[buildStatus status='SUCCESS' text='Skipped: compat-test module absent on this branch (likely main); run from v3 family instead.']"
                fi
            """.trimIndent()
        }
    }

    failureConditions {
        executionTimeoutMin = 60
    }

    features {
        xmlReport {
            reportType = XmlReport.XmlReportType.JUNIT
            rules = "+:components-registry-compat-test/build/test-results/test/*.xml"
        }
    }

    requirements {
        doesNotContain("env.OS_TYPE", "WIN", "RQ_2875")
    }

    // id15 is standalone-manual: ad-hoc prod-vs-QA via URLs, no auto-trigger,
    // not part of the release chain. Operator runs it on demand when they
    // want to spot-check two pre-deployed stands by URL.
    //
    // Explicit empty `dependencies {}` block to neutralise any snapshot
    // dependency the parent template (Octopus_OctopusGradleBuild) might
    // inject. id15 tests already-deployed services by URL — it does NOT
    // need anything compiled on the current branch's HEAD.
    dependencies {
    }

    // Disable the inherited triggers from Octopus_OctopusGradleBuild:
    //   TRIGGER_1003 — VCS Trigger (fires on every commit on every branch)
    //   TRIGGER_1006 — Schedule Trigger (weekly cron, Sundays 10:00 UTC)
    // id15 is operator-on-demand: any auto-fire would consume agent minutes
    // without operator-supplied URL parameters (which have no defaults),
    // producing either a misleading green build or a failure on main.
    disableSettings("TRIGGER_1003", "TRIGGER_1006")
})

// Compat — Trace Replay (prod traffic) — manual, on-demand. Replays the
// deduplicated production HTTP-traffic dump from the internal `crs-compat-trace` repo
// against a baseline (prod) and candidate (v3 stand) URL pair, recording
// diffs per tuple weighted by frequency.
//
// Two VCS roots:
//   1. ComponentsRegistry / GitHub root (inherited from the template) —
//      compat-test source code.
//   2. CrsCompatTrace (Bitbucket, internal) — the (count, METHOD, path) table
//      under `latest-top.txt`. Symlink to the most recent dated subdir.
//
// The TraceReplayCompatTest skips via Assumptions.assumeTrue when
// `COMPAT_TRACE_FILE` is unset, so the build always sets it explicitly.
//
// Distinct from id15CompatManual: weights diffs by real production traffic
// frequency (vs. uniform per-component matrix coverage). Both tests are
// complementary — keep one build type per concern so artifacts and timeouts
// don't collide.
object id16CompatTraceReplayManual : BuildType({
    templates(AbsoluteId("Octopus_OctopusGradleBuild"))
    id("16CompatTraceReplayManual")
    name = "[1.6] Compat — Trace Replay (prod traffic) [MANUAL]"

    artifactRules = """
        %ARTIFACT_PATH%
    """.trimIndent()

    vcs {
        // ComponentsRegistry / template's GitHub root is inherited; explicitly
        // attach the trace-data root with a checkout rule so the tuples land
        // under a predictable path relative to the build's checkout dir.
        root(CrsCompatTrace, "+:. => trace-data/")
    }

    params {
        text("COMPAT_BASELINE_URL", "", allowEmpty = false, display = ParameterDisplay.PROMPT)
        text("COMPAT_CANDIDATE_URL", "", allowEmpty = false, display = ParameterDisplay.PROMPT)
        text("COMPAT_RMS_URL", "", allowEmpty = false, display = ParameterDisplay.PROMPT)
        // Default `true` for this build type, unlike id15. The trace was
        // captured from a prod V1 stand and replaying it against today's
        // candidate (still serving V1 per the merge state) is by definition
        // a V1-vs-V1 measurement — the CANDIDATE_NOT_DB_MODE env-warning
        // would fire on every run and fail the build before the trace ever
        // executes. Operator flips to `false` once the candidate is in real
        // DB mode (`default-source=db`).
        text("COMPAT_ALLOW_NON_DB_CANDIDATE", "true", allowEmpty = false, display = ParameterDisplay.PROMPT)
        // `latest-top.txt` is a symlink (in the trace repo) to the most recent
        // dated subdir. Operator can override at run time (e.g. to a specific
        // snapshot for reproducibility).
        text("COMPAT_TRACE_FILE_RELATIVE", "latest-top.txt", allowEmpty = false, display = ParameterDisplay.PROMPT)
        // Real-body sidecar (post-bodies.ndjson) replayed by RealBodyReplayCompatTest.
        // Also a symlink in the trace repo; override for a pinned snapshot. If the
        // resolved file is absent the test SKIPs (assumeTrue), so this never fails id16.
        text("COMPAT_BODIES_FILE_RELATIVE", "latest-bodies.ndjson", allowEmpty = false, display = ParameterDisplay.PROMPT)
        param("ARTIFACT_PATH", """
            components-registry-compat-test/build/reports/compat/** => reports/compat
            components-registry-compat-test/build/test-results/**/*.xml => test-results/compat
        """.trimIndent())
        // `--tests <pattern>` is a TASK-level Gradle option — it must appear
        // AFTER the `:test` task on the command line. TC's gradle runner
        // composes the line as `gradlew <gradleParams> <tasks>`, so anything
        // in GRADLE_EXTRA_PARAMETERS lands BEFORE the tasks and Gradle treats
        // `--tests` as an unknown global option (observed in build 2.0.84-4:
        // "Unknown command-line option '--tests'"). Inline the filter into
        // GRADLE_TASK between the test and reporter task refs so the option
        // attaches to the correct task; do NOT move it back into the extras.
        param("GRADLE_TASK", ":components-registry-compat-test:test --tests *TraceReplayCompatTest* --tests *RealBodyReplayCompatTest* :components-registry-compat-test:compatibilityReporter")
        param("GRADLE_EXTRA_PARAMETERS", """
            -Pcompat.baseline.url=%COMPAT_BASELINE_URL%
            -Pcompat.candidate.url=%COMPAT_CANDIDATE_URL%
            -Pcompat.rms.url=%COMPAT_RMS_URL%
            -Pcompat.allow-non-db-candidate=%COMPAT_ALLOW_NON_DB_CANDIDATE%
            -Pcompat.trace.file=%teamcity.build.checkoutDir%/trace-data/%COMPAT_TRACE_FILE_RELATIVE%
            -Pcompat.bodies.file=%teamcity.build.checkoutDir%/trace-data/%COMPAT_BODIES_FILE_RELATIVE%
            -Pcompat.parallelism=10
        """.trimIndent())
    }

    steps {
        // Preflight guard — same rationale as id15CompatManual: if the operator
        // picks branch=main in the TC UI, the compat-test module is absent and
        // the template Gradle step would fail. Rewrite GRADLE_TASK to a no-op
        // via TC service message so the build is a green no-op with a clear
        // WARNING. See id15CompatManual for the mechanism details.
        script {
            name = "Preflight: skip-if-no-compat-test-module"
            id = "PRECHECK_COMPAT_MODULE"
            scriptContent = """
                #!/usr/bin/env bash
                set -eu
                if [ ! -d components-registry-compat-test ]; then
                  echo "::: components-registry-compat-test module not present in this checkout."
                  echo "::: id16 is only meaningful on v3-family branches that carry the compat-test infra."
                  echo "::: Rewriting GRADLE_TASK to 'help' so the template's Gradle step is a green no-op."
                  echo "##teamcity[setParameter name='GRADLE_TASK' value='help']"
                  echo "##teamcity[buildStatus status='SUCCESS' text='Skipped: compat-test module absent on this branch (likely main); run from v3 family instead.']"
                fi
            """.trimIndent()
        }
    }

    failureConditions {
        // 20 000 tuples × parallelism 10 took ~13 min in the 2026-05-17 run;
        // pad to 45 min for cold-cache agents + slower DB-mode candidate.
        // TODO(post-first-runs): revisit after 2-3 real TC runs establish
        //   an empirical ceiling on a DB-mode candidate under load. id15 uses
        //   60 min — bump here if 45 turns out to be marginal.
        executionTimeoutMin = 45
    }

    features {
        xmlReport {
            reportType = XmlReport.XmlReportType.JUNIT
            rules = "+:components-registry-compat-test/build/test-results/test/*.xml"
        }
    }

    requirements {
        doesNotContain("env.OS_TYPE", "WIN", "RQ_2875")
    }

    // id16 is standalone-manual like id15 — no chain dependency. The trace
    // replay runs against two already-deployed URLs the operator supplies.
    //
    // Explicit empty `dependencies {}` block to neutralise any snapshot
    // dependency the parent template (Octopus_OctopusGradleBuild) might
    // inject. id16 tests already-deployed services by URL — it does NOT
    // need anything compiled on the current branch's HEAD.
    dependencies {
    }

    // Disable the inherited triggers from Octopus_OctopusGradleBuild:
    //   TRIGGER_1003 — VCS Trigger (fires on every commit on every branch)
    //   TRIGGER_1006 — Schedule Trigger (weekly cron, Sundays 10:00 UTC)
    // id16 is operator-on-demand and replays a trace from the CrsCompatTrace
    // VCS root against URLs the operator supplies — any auto-fire would burn
    // agent minutes with no usable signal.
    disableSettings("TRIGGER_1003", "TRIGGER_1006")
})

// Compat — Local Stand (baseline + candidate JARs) — auto-fires after id10
// succeeds on non-main branches (see `triggers { finishBuildTrigger }` below)
// AND has a snapshot dep on id10. Operator can still Run manually from any
// branch — manual run from `main` is a tautological V1-vs-V1 measurement and
// is skipped server-side. Spins TWO CRS instances side-by-side
// on the agent: baseline (released `%LAST_RELEASE_VERSION%`, docker
// image from corp registry) and candidate (current chain's image
// pushed by id10), both pointed at the production Components-Registry
// DSL + service-config. Then runs the compat-test module's full
// endpoint matrix against both stands via the existing
// `scripts/local-stands/teamcity-run.sh` wrapper.
//
// Why docker-image extraction (not Artifactory pull) for the baseline:
// `components-registry-service-server` Maven publication ships the
// non-bootJar (integration-tests classifier, no embedded deps), which is
// not runnable. The bootJar — the runnable Spring-Boot fat JAR — lives only
// inside the docker image at `/app/app.jar` per the module's Dockerfile.
// Docker pull + `docker cp` is therefore the lowest-friction way to get
// a runnable baseline JAR onto the agent.
//
// VCS roots attached (additive to the template's GitHub root):
//   - ComponentsRegistry — prod DSL (master branch) under
//     `%COMPONENTS_REGISTRY_CHECKOUT_DIR%`, exactly like id20.
//   - ServiceConfig — internal service-config repo under `service-config/`.
//
// The wrapper handles postgres-up, both JVM boots, health-poll, compat
// invocation, and teardown. Its env contract is documented in
// `scripts/local-stands/TEAMCITY.md`.
object id17CompatLocalStandManual : BuildType({
    id("17CompatLocalStandManual")
    name = "[1.7] Compat — Local Stand (baseline + candidate JARs) [AUTO]"

    // Mirror id20's pattern: surface the upstream id10 build number as id17's
    // own build number so the TC dashboard / build chain UI shows the SAME
    // version tag across the whole chain (id10 = id17 = id20 = … = id70).
    // Without this id17 builds increment a separate counter and an operator
    // looking at the chain view sees disconnected numbers.
    buildNumberPattern = "%BUILD_NUMBER%"

    artifactRules = """
        **/build/reports/** => reports
        **/build/test-results/**/*.xml => test-results
        /tmp/crs-id17-%teamcity.build.id%/baseline.log => logs/baseline.log
        /tmp/crs-id17-%teamcity.build.id%/candidate.log => logs/candidate.log
        /tmp/compat-exec-logger-marker-*.txt => diag/
        /tmp/compat-test-report-dir.txt => diag/
    """.trimIndent()

    vcs {
        // id17 does NOT use Octopus_OctopusGradleBuild template (this is a
        // pure shell-script build, not a gradle one — the wrapper invokes
        // Gradle from inside) so the GitHub root is not inherited. Attach
        // it explicitly, same pattern as id20. Default `+:.` rule places
        // the CRS source (with scripts/local-stands/teamcity-run.sh) at
        // the checkout root.
        root(AbsoluteId("Octopus_OctopusComponents_OctopusGithubVcsRoot"))
        root(ComponentsRegistry, "+:. => %COMPONENTS_REGISTRY_CHECKOUT_DIR%")
        root(ServiceConfig, "+:. => service-config")
        // CrsCompatTrace ships the curated `components/{smoke,extended,all}.txt`
        // sets and the `versions/component-versions.json` snapshot so id17
        // doesn't need an RMS URL prompt or a CSV-paste of component names
        // from the operator every run.
        root(CrsCompatTrace, "+:. => trace-data/")
    }

    params {
        // Path (relative to the CrsCompatTrace checkout) to the file listing
        // component IDs to exercise. One ID per line; blank lines and lines
        // starting with `#` are ignored. `components/smoke.txt` is ~5 IDs and
        // `components/extended.txt` is ~50; default to `components/all.txt`
        // — the full sweep is what gives id17 actual signal (smoke produced
        // 0 ExecutionLogger entries because the 5 picks didn't intersect with
        // anything baseline V1 could resolve cleanly). Operator can still
        // narrow at run time via the prompt.
        text("COMPAT_COMPONENTS_FILE", "components/all.txt", allowEmpty = false, display = ParameterDisplay.PROMPT)
        // Default to the full endpoint matrix per business request: every
        // component × every compat-test class, no per-test skipping under
        // assumeTrue. Same prompt as before for operator overrides; flip to
        // `false` to bypass parameterised endpoints for a quick env check.
        text("COMPAT_FULL", "true", allowEmpty = false, display = ParameterDisplay.PROMPT)
        // JUnit 5 parallelism for the compat-test JVM. Default `8` matches
        // the historical compat.sh / gradle setup. Set `1` for a sequential
        // run when investigating flaky diff counts (id17 #3634 ran twice on
        // the same commit and produced 20 vs 22 active diffs — strong race
        // / VCS-refresh-cycle signal that disappears under sequential
        // execution). Lower values trade run time for determinism.
        text("COMPAT_PARALLELISM", "8", allowEmpty = false, display = ParameterDisplay.PROMPT)
        // Baseline version is the project-level `LAST_RELEASE_VERSION` (e.g.
        // `2.0.87`); pinned, not prompted — operator updates the project
        // param when a new release lands.
        param("COMPAT_BASELINE_VERSION", "%LAST_RELEASE_VERSION%")
        // Candidate image tag = the build number of the upstream id10 chain
        // step that just pushed it via `dockerPushImage`. Also reused as
        // %BUILD_NUMBER% so id17's own build number (set above via
        // `buildNumberPattern`) matches id10's.
        param("BUILD_NUMBER", "${id10CompileUtAuto.depParamRefs.buildNumber}")
        param("COMPAT_CANDIDATE_VERSION", "%BUILD_NUMBER%")
        // Corp docker registry hosting both image tags. Held in the TC
        // server / parent-project param `DOCKER_REGISTRY` (same value the
        // gradle Dockerfile arg consumes).
        param("DOCKER_REGISTRY_INTERNAL", "%DOCKER_REGISTRY%")
    }

    steps {
        script {
            name = "Extract JARs from docker images"
            id = "extract_jars"
            // Pull both images, `docker create` an ephemeral container per side,
            // `docker cp` the fat JAR into a build-namespaced /tmp dir, then
            // `docker rm`. The `trap` cleans up the create-container even if
            // `docker cp` fails (e.g. FS full, image lacks /app/app.jar), so
            // we don't leak orphan containers across failed re-runs (Opus
            // Stage-2 review). The work dir includes `%teamcity.build.id%`
            // (the numeric internal build ID, stable for a run's lifetime)
            // so two builds on a shared agent host never clobber each
            // other's JAR or log files.
            scriptContent = """
                set -euo pipefail
                BASELINE_IMAGE="%DOCKER_REGISTRY_INTERNAL%/octopusden/components-registry-service:%COMPAT_BASELINE_VERSION%"
                CANDIDATE_IMAGE="%DOCKER_REGISTRY_INTERNAL%/octopusden/components-registry-service:%COMPAT_CANDIDATE_VERSION%"
                WORK_DIR="/tmp/crs-id17-%teamcity.build.id%"
                BCID=""
                CCID=""
                cleanup() {
                    [ -n "${'$'}BCID" ] && docker rm -f "${'$'}BCID" >/dev/null 2>&1 || true
                    [ -n "${'$'}CCID" ] && docker rm -f "${'$'}CCID" >/dev/null 2>&1 || true
                }
                trap cleanup EXIT
                rm -rf "${'$'}WORK_DIR"
                mkdir -p "${'$'}WORK_DIR"
                echo "Pulling baseline:  ${'$'}BASELINE_IMAGE"
                docker pull "${'$'}BASELINE_IMAGE"
                echo "Pulling candidate: ${'$'}CANDIDATE_IMAGE"
                docker pull "${'$'}CANDIDATE_IMAGE"
                BCID=${'$'}(docker create "${'$'}BASELINE_IMAGE")
                docker cp "${'$'}BCID:/app/app.jar" "${'$'}WORK_DIR/baseline.jar"
                CCID=${'$'}(docker create "${'$'}CANDIDATE_IMAGE")
                docker cp "${'$'}CCID:/app/app.jar" "${'$'}WORK_DIR/candidate.jar"
                ls -lh "${'$'}WORK_DIR/baseline.jar" "${'$'}WORK_DIR/candidate.jar"
            """.trimIndent()
        }
        script {
            name = "Run local-stand compat"
            id = "run_compat"
            // BASELINE_LOG / CANDIDATE_LOG are namespaced under WORK_DIR
            // (same `%teamcity.build.id%` namespace as the extracted JARs)
            // so two concurrent id17 builds on a shared agent host don't
            // overwrite each other's wrapper logs. The artifact rules at
            // the top of this BuildType pick these paths up via the same
            // `%teamcity.build.id%` substitution. Ports stay at the wrapper
            // defaults (4567/4568); the git-mode sibling id18 uses the same
            // defaults but runs on a SEPARATE agent (own localhost + Docker),
            // so there is no port / Postgres collision between them.
            scriptContent = """
                set -euo pipefail
                # Green-skip when the compat-test infra isn't in this checkout. [1.7]/[1.8]
                # auto-fire on [1.0] success for ANY branch; if a build resolves to `main`
                # (the legacy V1 trunk) — or a deleted feature branch falls back to the
                # default branch — `scripts/local-stands/` is absent and the wrapper below
                # would exit 127. Mirror id15/id16: succeed with a clear status, not a red 127.
                if [ ! -f scripts/local-stands/teamcity-run.sh ]; then
                    echo "::: scripts/local-stands/teamcity-run.sh not present in this checkout."
                    echo "::: [1.7] is only meaningful on v3-family branches that carry the compat-test infra."
                    echo "##teamcity[buildStatus status='SUCCESS' text='Skipped: compat-test infra absent on this branch (likely main); run from v3 family instead.']"
                    exit 0
                fi
                WORK_DIR="/tmp/crs-id17-%teamcity.build.id%"
                TRACE_DATA_DIR="%teamcity.build.checkoutDir%/trace-data"

                # ---- VCS root diagnostics (temporary) ---------------------
                # Print what TC actually checked out into each secondary VCS
                # root dir. Catches misconfigured XXX_REPO_URL-style server
                # params (e.g. service-deployment vs service-config) and stale
                # git mirror caches that don't re-clone after a param value
                # change. (Names written without the percent delimiters here
                # so TC doesn't read the comment as an implicit-requirement
                # reference and mark every agent incompatible.)
                echo "===== id17 VCS checkout diagnostics ====="
                for d in "%teamcity.build.checkoutDir%/%COMPONENTS_REGISTRY_CHECKOUT_DIR%" \
                         "%teamcity.build.checkoutDir%/service-config" \
                         "${'$'}TRACE_DATA_DIR"; do
                    echo "--- ${'$'}d ---"
                    if [ -d "${'$'}d" ]; then
                        ls -la "${'$'}d" | head -25
                        if [ -d "${'$'}d/.git" ]; then
                            (cd "${'$'}d" && git remote -v && git log -1 --oneline 2>/dev/null) || true
                        fi
                    else
                        echo "(directory does not exist)"
                    fi
                done
                echo "===== /diagnostics ====="
                # ---- end VCS root diagnostics -----------------------------

                # Path-traversal guard on the prompted COMPAT_COMPONENTS_FILE
                # value. Manual TC builds are operated by trusted release
                # engineers, but a cheap defensive check beats a confusing
                # "no such file" trail when a typo or stray paste resolves to
                # something outside the CrsCompatTrace checkout. Reject any
                # value containing `..` (which would let the resolved path
                # escape trace-data/) or starting with `/` (absolute path).
                case "%COMPAT_COMPONENTS_FILE%" in
                    *..*|/*)
                        echo "ERROR: COMPAT_COMPONENTS_FILE=%COMPAT_COMPONENTS_FILE% must be a relative path inside trace-data/ (no '..' segments, no leading '/')" >&2
                        exit 2
                        ;;
                esac

                COMPONENTS_FILE_PATH="${'$'}TRACE_DATA_DIR/%COMPAT_COMPONENTS_FILE%"
                VERSIONS_FILE_PATH="${'$'}TRACE_DATA_DIR/versions/component-versions.json"

                # Fail-fast on missing files (Opus Stage-2 review). Without these
                # guards a checkout failure on the CrsCompatTrace VCS root would
                # silently produce zero components AND zero versions → every
                # per-component test would skip via assumeTrue → vacuous green.
                if [ ! -f "${'$'}COMPONENTS_FILE_PATH" ]; then
                    echo "ERROR: COMPAT_COMPONENTS_FILE=${'$'}COMPONENTS_FILE_PATH does not exist (CrsCompatTrace checkout failed or file renamed)" >&2
                    exit 2
                fi
                if [ ! -f "${'$'}VERSIONS_FILE_PATH" ]; then
                    echo "ERROR: versions snapshot ${'$'}VERSIONS_FILE_PATH does not exist (CrsCompatTrace checkout failed or path renamed)" >&2
                    exit 2
                fi

                # Read the components file: strip CRs (Windows-saved files),
                # trim per-line whitespace, drop blank and comment-only lines.
                # awk is one pass and won't trip set-e's pipefail on no-match.
                TMP_LIST=${'$'}(awk '
                    { sub(/\r${'$'}/, ""); gsub(/^[[:space:]]+|[[:space:]]+${'$'}/, "") }
                    ${'$'}0 == "" || /^#/ { next }
                    { print }
                ' "${'$'}COMPONENTS_FILE_PATH")
                if [ -z "${'$'}TMP_LIST" ]; then
                    echo "ERROR: COMPAT_COMPONENTS_FILE=${'$'}COMPONENTS_FILE_PATH produced zero IDs after filtering blanks/comments" >&2
                    exit 2
                fi
                COMPONENTS_CSV=${'$'}(printf '%s' "${'$'}TMP_LIST" | tr '\n' ',')
                # Strip the trailing comma that `tr` adds when the input has a
                # final newline (which `awk` always emits unless the input was
                # empty — guarded above).
                COMPONENTS_CSV="${'$'}{COMPONENTS_CSV%,}"
                COMPONENTS_COUNT=${'$'}(printf '%s\n' "${'$'}TMP_LIST" | wc -l | tr -d ' ')
                echo "Component set:   %COMPAT_COMPONENTS_FILE% (${'$'}COMPONENTS_COUNT IDs)"
                echo "Versions file:   ${'$'}VERSIONS_FILE_PATH"
                export BASELINE_JAR="${'$'}WORK_DIR/baseline.jar"
                export CANDIDATE_JAR="${'$'}WORK_DIR/candidate.jar"
                export BASELINE_LOG="${'$'}WORK_DIR/baseline.log"
                export CANDIDATE_LOG="${'$'}WORK_DIR/candidate.log"
                export LOCAL_VCS_ROOT="%teamcity.build.checkoutDir%/%COMPONENTS_REGISTRY_CHECKOUT_DIR%"
                export SERVICE_CONFIG_DIR="%teamcity.build.checkoutDir%/service-config"
                export COMPAT_SMOKE_COMPONENTS="${'$'}COMPONENTS_CSV"
                # VersionSampler reads from the file directly (no RMS round-trip
                # per call) when this is set. See loadVersionsFile in
                # components-registry-compat-test for the contract.
                export COMPAT_VERSIONS_FILE="${'$'}VERSIONS_FILE_PATH"
                export COMPAT_FULL="%COMPAT_FULL%"
                export COMPAT_PARALLELISM="%COMPAT_PARALLELISM%"
                # Real captured POST bodies (post-bodies.ndjson) replayed by
                # RealBodyReplayCompatTest. Fail-FAST on a missing sidecar (Stage-2
                # review): the trace repo's master carries latest-bodies.ndjson, so
                # an absent file means the CrsCompatTrace checkout is broken. Fail-soft
                # would silently SKIP the real-body replay and a green build would be
                # indistinguishable from one that actually replayed the real bodies.
                BODIES_FILE_PATH="${'$'}TRACE_DATA_DIR/latest-bodies.ndjson"
                if [ ! -f "${'$'}BODIES_FILE_PATH" ]; then
                    echo "ERROR: real-body sidecar ${'$'}BODIES_FILE_PATH does not exist (CrsCompatTrace checkout failed or post-bodies.ndjson missing)" >&2
                    exit 2
                fi
                export COMPAT_BODIES_FILE="${'$'}BODIES_FILE_PATH"
                echo "Bodies file:     ${'$'}BODIES_FILE_PATH"
                # Deduplicated production trace (latest-top.txt) replayed by
                # TraceReplayCompatTest. [1.7]/[1.8] are the AUTO gate, so cap the
                # replay at the top-30000 most-frequent business tuples (the file is
                # ranked desc; id16 replays it unlimited). Fail-FAST on a missing file,
                # same rationale as the bodies sidecar above.
                TRACE_FILE_PATH="${'$'}TRACE_DATA_DIR/latest-top.txt"
                if [ ! -f "${'$'}TRACE_FILE_PATH" ]; then
                    echo "ERROR: trace file ${'$'}TRACE_FILE_PATH does not exist (CrsCompatTrace checkout failed or top-N trace missing)" >&2
                    exit 2
                fi
                export COMPAT_TRACE_FILE="${'$'}TRACE_FILE_PATH"
                export COMPAT_TRACE_LIMIT=30000
                echo "Trace file:      ${'$'}TRACE_FILE_PATH (limit 30000)"
                # Route the postgres pull through the artifactory mirror so
                # id17 doesn't hit Docker Hub's anonymous-pull rate limit
                # (#3636 failed mid-Stage-1 with `toomanyrequests`). The
                # docker-compose.local-postgres.yml accepts `POSTGRES_IMAGE`
                # with a `postgres:16` fallback for developers running
                # outside TC.
                export POSTGRES_IMAGE="%DOCKER_REGISTRY_INTERNAL%/postgres:16"
                export RESET_DB=1
                export COMPONENTS_REGISTRY_SERVICE_VERSION="%COMPAT_BASELINE_VERSION%"
                export BUILD_VERSION="%COMPAT_CANDIDATE_VERSION%"
                bash scripts/local-stands/teamcity-run.sh
            """.trimIndent()
        }
    }

    failureConditions {
        // Full sweep budget. With `components/all.txt` (~475 IDs) and
        // COMPAT_FULL=true the per-test-class matrix is ~30-50 min after
        // cold-pull + postgres + auto-migrate (~5-7 min upfront). Headroom
        // for slow agents or congested registry → cap at 120 min.
        executionTimeoutMin = 120
    }

    features {
        xmlReport {
            reportType = XmlReport.XmlReportType.JUNIT
            rules = "+:components-registry-compat-test/build/test-results/test/*.xml"
        }
        // Docker Support: pre-login to the corporate Artifactory registry
        // connections before the `extract_jars` script runs. id17 pulls a
        // snapshot tag (id10's build number) which requires authenticated
        // read access; release tags happen to be anonymously pullable, but
        // snapshots are not. id10 inherits this from Octopus_OctopusGradleBuild
        // template; id17 is template-less (pure shell-script) so it needs
        // the feature declared explicitly.
        //
        // WARNING: dockerRegistryId is a comma-separated list of TC-internal
        // ProjectFeature external IDs of the Docker Registry connections
        // (under <parent project>/Connections). These IDs are SPECIFIC to
        // THIS TeamCity installation — different TC servers will have
        // different auto-generated PROJECT_EXT_xxx IDs for the same logical
        // registries. If this DSL is ever applied on a different TC server
        // (fork, DR site, migration), look up the equivalent connection IDs
        // there and replace the list below. The values below map to:
        //   - Artifactory Universal Registry (docker.artifactory.<host>)
        //   - <two other corp registries, ID-aliased the same way>
        // Listing all three is the conservative default — id17 only needs
        // the Artifactory one, but logging into the sibling registries is
        // a no-op cost if they're never pulled from.
        dockerSupport {
            id = "DockerSupport"
            loginToRegistry = on {
                dockerRegistryId = "PROJECT_EXT_177,PROJECT_EXT_350,PROJECT_EXT_351"
            }
        }
    }

    requirements {
        // The wrapper relies on docker-compose, lsof, bash, and a local
        // postgres on a Linux/macOS host. Same Windows exclusion as the
        // sibling compat configs.
        doesNotContain("env.OS_TYPE", "WIN", "RQ_2875")
    }

    triggers {
        // Auto-fire id17 whenever id10 finishes successfully on a branch
        // OTHER than main. v3 is the schema-v2 trunk where local-stand
        // compat actually has signal; on main the legacy V1 candidate vs
        // V1 baseline is a tautological pass and would just burn agent
        // minutes (cold pulls, postgres init, two stand boots). Manual
        // Run from any branch — including main — still works because
        // the snapshot dep is the only hard requirement.
        finishBuildTrigger {
            buildType = "${id10CompileUtAuto.id}"
            successfulOnly = true
            branchFilter = """
                +:*
                -:main
            """.trimIndent()
        }
    }

    dependencies {
        snapshot(id10CompileUtAuto) {
            onDependencyFailure = FailureAction.CANCEL
        }
    }
})

// Git-mode sibling of id17. Boots the SAME baseline + candidate JARs, but runs
// the candidate WITHOUT a database (CANDIDATE_MODE=git → the `no-db` profile +
// --auto-migrate=false --default-source=git): the JDBC/JPA/Flyway auto-configs are
// excluded so the candidate needs no Postgres, and every v1/v2/v3 response is served
// by the Git resolver — the same code path as the 2.0.87 baseline. This asserts the
// deploy-without-migration NO-OP invariant — deploying v3 and not migrating must be
// byte-identical to the old version for v1/v2/v3. It uses the (empty) git-mode
// known-deltas file, so any active diff is a real regression of that invariant.
//
// Differs from id17 by: id/name, the /tmp/crs-id18-* work namespace, and
// `export CANDIDATE_MODE=git` before the wrapper (which selects the no-db,
// no-Postgres candidate). Runs on a separate agent from id17 so the shared
// default ports (4567/4568) don't collide.
object id18CompatLocalStandGitModeAuto : BuildType({
    id("18CompatLocalStandGitModeAuto")
    name = "[1.8] Compat — Local Stand (no DB migration, git-mode) [AUTO]"

    // Surface the upstream id10 build number as id18's own (chain view shows the
    // SAME version tag across id10 = id18 = …), mirroring id17.
    buildNumberPattern = "%BUILD_NUMBER%"

    artifactRules = """
        **/build/reports/** => reports
        **/build/test-results/**/*.xml => test-results
        /tmp/crs-id18-%teamcity.build.id%/baseline.log => logs/baseline.log
        /tmp/crs-id18-%teamcity.build.id%/candidate.log => logs/candidate.log
        /tmp/compat-exec-logger-marker-*.txt => diag/
        /tmp/compat-test-report-dir.txt => diag/
    """.trimIndent()

    vcs {
        // Pure shell-script build (no Octopus_OctopusGradleBuild template), same
        // as id17 — attach the GitHub root + secondary roots explicitly.
        root(AbsoluteId("Octopus_OctopusComponents_OctopusGithubVcsRoot"))
        root(ComponentsRegistry, "+:. => %COMPONENTS_REGISTRY_CHECKOUT_DIR%")
        root(ServiceConfig, "+:. => service-config")
        root(CrsCompatTrace, "+:. => trace-data/")
    }

    params {
        text("COMPAT_COMPONENTS_FILE", "components/all.txt", allowEmpty = false, display = ParameterDisplay.PROMPT)
        text("COMPAT_FULL", "true", allowEmpty = false, display = ParameterDisplay.PROMPT)
        text("COMPAT_PARALLELISM", "8", allowEmpty = false, display = ParameterDisplay.PROMPT)
        param("COMPAT_BASELINE_VERSION", "%LAST_RELEASE_VERSION%")
        param("BUILD_NUMBER", "${id10CompileUtAuto.depParamRefs.buildNumber}")
        param("COMPAT_CANDIDATE_VERSION", "%BUILD_NUMBER%")
        param("DOCKER_REGISTRY_INTERNAL", "%DOCKER_REGISTRY%")
    }

    steps {
        script {
            name = "Extract JARs from docker images"
            id = "extract_jars"
            scriptContent = """
                set -euo pipefail
                BASELINE_IMAGE="%DOCKER_REGISTRY_INTERNAL%/octopusden/components-registry-service:%COMPAT_BASELINE_VERSION%"
                CANDIDATE_IMAGE="%DOCKER_REGISTRY_INTERNAL%/octopusden/components-registry-service:%COMPAT_CANDIDATE_VERSION%"
                WORK_DIR="/tmp/crs-id18-%teamcity.build.id%"
                BCID=""
                CCID=""
                cleanup() {
                    [ -n "${'$'}BCID" ] && docker rm -f "${'$'}BCID" >/dev/null 2>&1 || true
                    [ -n "${'$'}CCID" ] && docker rm -f "${'$'}CCID" >/dev/null 2>&1 || true
                }
                trap cleanup EXIT
                rm -rf "${'$'}WORK_DIR"
                mkdir -p "${'$'}WORK_DIR"
                echo "Pulling baseline:  ${'$'}BASELINE_IMAGE"
                docker pull "${'$'}BASELINE_IMAGE"
                echo "Pulling candidate: ${'$'}CANDIDATE_IMAGE"
                docker pull "${'$'}CANDIDATE_IMAGE"
                BCID=${'$'}(docker create "${'$'}BASELINE_IMAGE")
                docker cp "${'$'}BCID:/app/app.jar" "${'$'}WORK_DIR/baseline.jar"
                CCID=${'$'}(docker create "${'$'}CANDIDATE_IMAGE")
                docker cp "${'$'}CCID:/app/app.jar" "${'$'}WORK_DIR/candidate.jar"
                ls -lh "${'$'}WORK_DIR/baseline.jar" "${'$'}WORK_DIR/candidate.jar"
            """.trimIndent()
        }
        script {
            name = "Run local-stand compat (git-mode)"
            id = "run_compat"
            // Same wrapper as id17, namespaced under /tmp/crs-id18-%teamcity.build.id%
            // and with CANDIDATE_MODE=git exported so the candidate boots with the no-db
            // profile (no database, no migration, default-source=git; issue #310).
            // Runs on a separate agent from id17 so the shared default ports don't collide.
            scriptContent = """
                set -euo pipefail
                # Green-skip when the compat-test infra isn't in this checkout. [1.7]/[1.8]
                # auto-fire on [1.0] success for ANY branch; if a build resolves to `main`
                # (the legacy V1 trunk) — or a deleted feature branch falls back to the
                # default branch — `scripts/local-stands/` is absent and the wrapper below
                # would exit 127. Mirror id15/id16: succeed with a clear status, not a red 127.
                if [ ! -f scripts/local-stands/teamcity-run.sh ]; then
                    echo "::: scripts/local-stands/teamcity-run.sh not present in this checkout."
                    echo "::: [1.8] is only meaningful on v3-family branches that carry the compat-test infra."
                    echo "##teamcity[buildStatus status='SUCCESS' text='Skipped: compat-test infra absent on this branch (likely main); run from v3 family instead.']"
                    exit 0
                fi
                WORK_DIR="/tmp/crs-id18-%teamcity.build.id%"
                TRACE_DATA_DIR="%teamcity.build.checkoutDir%/trace-data"

                echo "===== id18 VCS checkout diagnostics ====="
                for d in "%teamcity.build.checkoutDir%/%COMPONENTS_REGISTRY_CHECKOUT_DIR%" \
                         "%teamcity.build.checkoutDir%/service-config" \
                         "${'$'}TRACE_DATA_DIR"; do
                    echo "--- ${'$'}d ---"
                    if [ -d "${'$'}d" ]; then
                        ls -la "${'$'}d" | head -25
                        if [ -d "${'$'}d/.git" ]; then
                            (cd "${'$'}d" && git remote -v && git log -1 --oneline 2>/dev/null) || true
                        fi
                    else
                        echo "(directory does not exist)"
                    fi
                done
                echo "===== /diagnostics ====="

                case "%COMPAT_COMPONENTS_FILE%" in
                    *..*|/*)
                        echo "ERROR: COMPAT_COMPONENTS_FILE=%COMPAT_COMPONENTS_FILE% must be a relative path inside trace-data/ (no '..' segments, no leading '/')" >&2
                        exit 2
                        ;;
                esac

                COMPONENTS_FILE_PATH="${'$'}TRACE_DATA_DIR/%COMPAT_COMPONENTS_FILE%"
                VERSIONS_FILE_PATH="${'$'}TRACE_DATA_DIR/versions/component-versions.json"

                if [ ! -f "${'$'}COMPONENTS_FILE_PATH" ]; then
                    echo "ERROR: COMPAT_COMPONENTS_FILE=${'$'}COMPONENTS_FILE_PATH does not exist (CrsCompatTrace checkout failed or file renamed)" >&2
                    exit 2
                fi
                if [ ! -f "${'$'}VERSIONS_FILE_PATH" ]; then
                    echo "ERROR: versions snapshot ${'$'}VERSIONS_FILE_PATH does not exist (CrsCompatTrace checkout failed or path renamed)" >&2
                    exit 2
                fi

                TMP_LIST=${'$'}(awk '
                    { sub(/\r${'$'}/, ""); gsub(/^[[:space:]]+|[[:space:]]+${'$'}/, "") }
                    ${'$'}0 == "" || /^#/ { next }
                    { print }
                ' "${'$'}COMPONENTS_FILE_PATH")
                if [ -z "${'$'}TMP_LIST" ]; then
                    echo "ERROR: COMPAT_COMPONENTS_FILE=${'$'}COMPONENTS_FILE_PATH produced zero IDs after filtering blanks/comments" >&2
                    exit 2
                fi
                COMPONENTS_CSV=${'$'}(printf '%s' "${'$'}TMP_LIST" | tr '\n' ',')
                COMPONENTS_CSV="${'$'}{COMPONENTS_CSV%,}"
                COMPONENTS_COUNT=${'$'}(printf '%s\n' "${'$'}TMP_LIST" | wc -l | tr -d ' ')
                echo "Component set:   %COMPAT_COMPONENTS_FILE% (${'$'}COMPONENTS_COUNT IDs)"
                echo "Versions file:   ${'$'}VERSIONS_FILE_PATH"
                export BASELINE_JAR="${'$'}WORK_DIR/baseline.jar"
                export CANDIDATE_JAR="${'$'}WORK_DIR/candidate.jar"
                export BASELINE_LOG="${'$'}WORK_DIR/baseline.log"
                export CANDIDATE_LOG="${'$'}WORK_DIR/candidate.log"
                export LOCAL_VCS_ROOT="%teamcity.build.checkoutDir%/%COMPONENTS_REGISTRY_CHECKOUT_DIR%"
                export SERVICE_CONFIG_DIR="%teamcity.build.checkoutDir%/service-config"
                export COMPAT_SMOKE_COMPONENTS="${'$'}COMPONENTS_CSV"
                export COMPAT_VERSIONS_FILE="${'$'}VERSIONS_FILE_PATH"
                export COMPAT_FULL="%COMPAT_FULL%"
                export COMPAT_PARALLELISM="%COMPAT_PARALLELISM%"
                # Real captured POST bodies (post-bodies.ndjson) replayed by
                # RealBodyReplayCompatTest. Fail-FAST on a missing sidecar (Stage-2
                # review): the trace repo's master carries latest-bodies.ndjson, so
                # an absent file means the CrsCompatTrace checkout is broken. Fail-soft
                # would silently SKIP the real-body replay and a green build would be
                # indistinguishable from one that actually replayed the real bodies.
                BODIES_FILE_PATH="${'$'}TRACE_DATA_DIR/latest-bodies.ndjson"
                if [ ! -f "${'$'}BODIES_FILE_PATH" ]; then
                    echo "ERROR: real-body sidecar ${'$'}BODIES_FILE_PATH does not exist (CrsCompatTrace checkout failed or post-bodies.ndjson missing)" >&2
                    exit 2
                fi
                export COMPAT_BODIES_FILE="${'$'}BODIES_FILE_PATH"
                echo "Bodies file:     ${'$'}BODIES_FILE_PATH"
                # Deduplicated production trace (latest-top.txt) replayed by
                # TraceReplayCompatTest. [1.7]/[1.8] are the AUTO gate, so cap the
                # replay at the top-30000 most-frequent business tuples (the file is
                # ranked desc; id16 replays it unlimited). Fail-FAST on a missing file,
                # same rationale as the bodies sidecar above.
                TRACE_FILE_PATH="${'$'}TRACE_DATA_DIR/latest-top.txt"
                if [ ! -f "${'$'}TRACE_FILE_PATH" ]; then
                    echo "ERROR: trace file ${'$'}TRACE_FILE_PATH does not exist (CrsCompatTrace checkout failed or top-N trace missing)" >&2
                    exit 2
                fi
                export COMPAT_TRACE_FILE="${'$'}TRACE_FILE_PATH"
                export COMPAT_TRACE_LIMIT=30000
                echo "Trace file:      ${'$'}TRACE_FILE_PATH (limit 30000)"
                # git-mode (no-db, issue #310) does NOT start Postgres, so no POSTGRES_IMAGE
                # pull is needed. RESET_DB=1 only drives teamcity-run.sh's pre-run teardown
                # of any stale Postgres left by a prior db-mode run on this (persistent) agent.
                export RESET_DB=1
                export COMPONENTS_REGISTRY_SERVICE_VERSION="%COMPAT_BASELINE_VERSION%"
                export BUILD_VERSION="%COMPAT_CANDIDATE_VERSION%"
                # No-migration / no-DB git-routing mode — the only behavioural delta vs id17.
                export CANDIDATE_MODE=git
                bash scripts/local-stands/teamcity-run.sh
            """.trimIndent()
        }
    }

    failureConditions {
        // Git-mode skips auto-migrate AND Postgres, so the candidate comes up faster
        // than id17; keep a generous cap for cold image pulls + two stand boots.
        executionTimeoutMin = 120
    }

    features {
        xmlReport {
            reportType = XmlReport.XmlReportType.JUNIT
            rules = "+:components-registry-compat-test/build/test-results/test/*.xml"
        }
        // Same Artifactory pre-login as id17 (snapshot candidate tag needs auth).
        // See id17 for the dockerRegistryId caveat (IDs are TC-installation-specific).
        dockerSupport {
            id = "DockerSupport"
            loginToRegistry = on {
                dockerRegistryId = "PROJECT_EXT_177,PROJECT_EXT_350,PROJECT_EXT_351"
            }
        }
    }

    requirements {
        doesNotContain("env.OS_TYPE", "WIN", "RQ_2875")
    }

    triggers {
        // Auto-fire alongside id17 whenever id10 finishes on a non-main branch.
        // git-mode has signal on every branch (it guards the no-op invariant),
        // but mirror id17's main exclusion to avoid burning agent minutes on the
        // release trunk; Manual Run from any branch still works.
        finishBuildTrigger {
            buildType = "${id10CompileUtAuto.id}"
            successfulOnly = true
            branchFilter = """
                +:*
                -:main
            """.trimIndent()
        }
    }

    dependencies {
        snapshot(id10CompileUtAuto) {
            onDependencyFailure = FailureAction.CANCEL
        }
    }
})

object id20ValidateComponentsRegistryProductionDataAuto : BuildType({
    id("20ValidateComponentsRegistryProductionDataAuto")
    name = "[2.0] Validate Production Data [AUTO]"

    artifactRules = "%COMPONENTS_REGISTRY_CHECKOUT_DIR% => %COMPONENTS_REGISTRY_CHECKOUT_DIR%"
    buildNumberPattern = "%BUILD_NUMBER%"

    params {
        param("BUILD_NUMBER", "${id10CompileUtAuto.depParamRefs.buildNumber}")
    }

    vcs {
        root(AbsoluteId("Octopus_OctopusComponents_OctopusGithubVcsRoot"))
        root(ComponentsRegistry, "+:. => %COMPONENTS_REGISTRY_CHECKOUT_DIR%")
    }

    steps {
        gradle {
            tasks = "validateConfig"
            buildFile = "build.gradle"
            workingDir = "%COMPONENTS_REGISTRY_CHECKOUT_DIR%"
            gradleParams = """
                --info
                -PtargetPath=%teamcity.build.workingDir%/src/main/resources
                -Pcomponents-registry-service.version=%BUILD_NUMBER%
                -Puse_dev_repository=all
                -Pemployee-service.url=%EMPLOYEE_SERVICE_URL%
                -Pemployee-service.token=%EMPLOYEE_SERVICE_TOKEN%
            """.trimIndent()
            enableStacktrace = true
            jdkHome = "%env.JAVA_HOME%"
            jvmArgs = "-Duser.home=%teamcity.agent.jvm.user.home%"
            param("org.jfrog.artifactory.selectedDeployableServer.defaultModuleVersionConfiguration", "GLOBAL")
        }
    }

    triggers {
        finishBuildTrigger {
            buildType = "${id10CompileUtAuto.id}"
            successfulOnly = true
            branchFilter = "+:*"
        }
    }

    features {
        swabra {
        }
    }

    dependencies {
        snapshot(id10CompileUtAuto) {
            onDependencyFailure = FailureAction.FAIL_TO_START
        }
        artifacts(AbsoluteId("bt774")) {
            buildRule = lastSuccessful()
            artifactRules = ".teamcity/settings/digest.txt"
        }
    }
})

object id30DeployToOkdQaDevAuto : BuildType({
    templates(AbsoluteId("RnDProcessesAutomation_IdpComponentOkdDeploy"))
    id("30DeployToOkdQaDevAuto")
    name = "[3.0] Deploy to OKD QA DEV [AUTO]"

    params {
        param("OKD_SERVER_URL", "%OKD_SERVER_DEV_URL%")
        param("BUILD_NUMBER", "${id10CompileUtAuto.depParamRefs.buildNumber}")
        param("TEAMS_NOTIFICATION_CHANNEL", "")
        param("OKD_SA_TOKEN", "%OKD_SA_QA_TOKEN%")
    }

    triggers {
        finishBuildTrigger {
            id = "TRIGGER_1015"
            buildType = "${id20ValidateComponentsRegistryProductionDataAuto.id}"
            successfulOnly = true
        }
    }

    dependencies {
        snapshot(id20ValidateComponentsRegistryProductionDataAuto) {
        }
        // The heavy DB/integration suite [1.2] must pass before we deploy (it runs in
        // parallel off [1.0], so by deploy time it's done). The image comes from [1.0].
        snapshot(id12IntegrationDbTestsAuto) {
            onDependencyFailure = FailureAction.FAIL_TO_START
        }
    }
})

// Post-deploy: trigger Git→DB component migration on the freshly-deployed
// QA pod. Idempotent and tolerant — exits 0 (skip, don't fail) if the
// /admin/migration-status endpoint is missing (pre-v3 build) or returns
// git==0 (nothing left to migrate). Fails the build only when migration
// was attempted and ended in a FAILED state, so the QA-deploy chain
// surfaces real migration breakage but does not break on routine
// redeploys of an already-migrated stand.
//
// Pure shell-script build (no Gradle template) — VCS root is attached
// explicitly, same pattern as id17 / id20. Script source lives at
// `scripts/teamcity/qa-post-deploy-migrate.sh` so it can be tested and
// reviewed outside TC.
//
// Auth: %CRS_QA_ADMIN_TOKEN% — the RAW Keycloak JWT for a service account
// with the IMPORT_DATA role (the token value only, no `Authorization: ` /
// no `Bearer ` prefix — the script adds `Authorization: Bearer <token>`
// itself, and defensively strips a leading `Bearer ` if it was included).
// The param is declared as a placeholder here; the actual secret is
// configured on the TC server as a project-level password parameter named
// `CRS_QA_ADMIN_TOKEN`. Until that secret is provisioned this build will
// fail with a clear "auth rejected" message in the script — that is
// intentional (no silent-skip on auth misconfig).
object id31MigrateOnQaDevAuto : BuildType({
    id("31MigrateOnQaDevAuto")
    name = "[3.1] Migrate components on QA DEV [AUTO]"

    vcs {
        root(AbsoluteId("Octopus_OctopusComponents_OctopusGithubVcsRoot"))
    }

    params {
        // QA route to the freshly-deployed CRS pod. Sourced from the
        // server-level param `CRS_QA_DEV_BASE_URL` (configure once on
        // the TC parent project, same pattern as OKD_SERVER_DEV_URL).
        param("env.CRS_BASE_URL", "%CRS_QA_DEV_BASE_URL%")
        // Raw Keycloak JWT (service-account, role IMPORT_DATA) — token
        // value only, NO `Bearer ` prefix; the script adds the scheme.
        // PLACEHOLDER — the project-level password parameter
        // `CRS_QA_ADMIN_TOKEN` must be provisioned on the TC server
        // before this build will succeed.
        password("env.CRS_ADMIN_TOKEN", "%CRS_QA_ADMIN_TOKEN%")
    }

    steps {
        script {
            name = "Trigger Git→DB migration on QA"
            id = "RUNNER_31_MIGRATE"
            // The script file lives in the repo. Working dir is the
            // checkout root; the +x bit is preserved in git but we
            // restore it defensively in case the checkout strips mode.
            scriptContent = """
                chmod +x scripts/teamcity/qa-post-deploy-migrate.sh
                exec scripts/teamcity/qa-post-deploy-migrate.sh
            """.trimIndent()
        }
    }

    triggers {
        finishBuildTrigger {
            id = "TRIGGER_31_AFTER_DEPLOY"
            buildType = "${id30DeployToOkdQaDevAuto.id}"
            successfulOnly = true
        }
    }

    dependencies {
        snapshot(id30DeployToOkdQaDevAuto) {
            onDependencyFailure = FailureAction.FAIL_TO_START
        }
    }

    failureConditions {
        executionTimeoutMin = 45
    }

    requirements {
        doesNotContain("env.OS_TYPE", "WIN", "RQ_2875")
    }
})

object id40ReleaseManual : BuildType({
    templates(AbsoluteId("Octopus_OctopusComponents_OctopusRelease"))
    id("40ReleaseManual")
    name = "[4.0] Release [MANUAL]"

    params {
        param("CURRENT_COMMIT", "${id10CompileUtAuto.depParamRefs["CURRENT_COMMIT"]}")
        param("BUILD_NUMBER", "${id10CompileUtAuto.depParamRefs.buildNumber}")
        param("PROJECT_VERSION", "${id10CompileUtAuto.depParamRefs["PROJECT_VERSION"]}")
        param("env.OCTOPUS_GITHUB_TOKEN", "%OCTOPUS_GITHUB_TOKEN%")
    }

    vcs {
        excludeDefaultBranchChanges = true
    }

    steps {
        kotlinFile {
            name = "Call GitHub Release (Kotlin)"
            id = "RUNNER_155"
            path = "octopus-base/teamcity/scripts/CallGitHubRelease.main.kts"
            compiler = "%teamcity.tool.kotlin.compiler.1.5.32%"
            arguments = "%OCTOPUS_MODULE_NAME% %OCTOPUS_GITHUB_TOKEN% %CURRENT_COMMIT% %PROJECT_VERSION% %OCTOPUS_RELEASE_TIMEOUT% %OCTOPUS_RELEASE_EVENT_TYPE%"
            jdkHome = "%env.JDK_18%"
        }
    }

    dependencies {
        snapshot(id30DeployToOkdQaDevAuto) {
            reuseBuilds = ReuseBuilds.NO
            onDependencyFailure = FailureAction.FAIL_TO_START
        }
    }
})

object id50ReleasePostProcessingAuto : BuildType({
    templates(AbsoluteId("Octopus_OctopusComponents_HOctopusTest_OctopusReleasePostProcessing"))
    id("50ReleasePostProcessingAuto")
    name = "[5.0] Release Post Processing [AUTO]"

    params {
        param("env.JAVA_HOME", "%env.JDK_ZULU_17_x64%")
    }
})

object id60DeployToOkdQaGhAuto : BuildType({
    templates(AbsoluteId("RnDProcessesAutomation_IdpComponentOkdDeploy"))
    id("60DeployToOkdQaGhAuto")
    name = "[6.0] Deploy to OKD QA GH [AUTO]"

    params {
        param("OKD_SERVER_URL", "%OKD_SERVER_DEV_URL%")
        param("BUILD_NUMBER", "${id50ReleasePostProcessingAuto.depParamRefs.buildNumber}")
        param("TEAMS_NOTIFICATION_CHANNEL", "")
        param("OKD_SA_TOKEN", "%OKD_SA_QA_TOKEN%")
    }

    triggers {
        finishBuildTrigger {
            id = "TRIGGER_18"
            buildType = "${id50ReleasePostProcessingAuto.id}"
            successfulOnly = true
        }
    }

    dependencies {
        snapshot(id50ReleasePostProcessingAuto) {
            onDependencyFailure = FailureAction.FAIL_TO_START
        }
    }
})

object id70DeployToOkdProdManual_2 : BuildType({
    templates(AbsoluteId("RnDProcessesAutomation_IdpComponentOkdDeploy"))
    id("70DeployToOkdProdManual_2")
    name = "[7.0] Deploy to OKD PROD [MANUAL]"

    params {
        param("TEAMCITY_UPDATE_PROJECT_IDS", "%RELEASE_DOWNSTREAM_TC_PROJECT_IDS%")
        param("TEAMCITY_UPDATE_BUILD_CONFIGURATION_IDS", "")
        param("OKD_SERVER_URL", "%OKD_SERVER_PROD_URL%")
        param("BUILD_VERSION", "%BUILD_NUMBER%")
        param("ESCROW_AUTOMATION_TOOL_LINK", "%ESCROW_AUTOMATION_TOOL_URL%")
        param("TEAMCITY_UPDATE_PARAMETER_NAME", "COMPONENTS_REGISTRY_SERVICE_VERSION")
        param("WIKI_USER", "%WIKI_RELENG_USER%")
        param("WIKI_PAGE_HEADER", "%COMPONENTS_REGISTRY_WIKI_PAGE_HEADER%")
        param("WIKI_SPACE_KEY", "%COMPONENTS_REGISTRY_WIKI_SPACE_KEY%")
        param("WIKI_PAGE_ID", "%COMPONENTS_REGISTRY_WIKI_PAGE_ID%")
        param("DEPLOYMENT_ENVIRONMENT", "production")
        param("COMPONENTS_REGISTRY_VALIDATION_LINK", "%COMPONENTS_REGISTRY_VALIDATION_URL%")
        param("TEAMCITY_UPDATE_PARAMETER_VALUE", "%BUILD_NUMBER%")
        param("OKD_SA_TOKEN", "%OKD_SA_PROD_TOKEN%")
        param("COMPONENTS_REGISTRY_LINK", "%COMPONENTS_REGISTRY_BROWSE_URL%")
        param("BUILD_NUMBER", "${id60DeployToOkdQaGhAuto.depParamRefs["BUILD_NUMBER"]}")
        param("RELEASE_MANAGEMENT_AUTOMATION_LINK", "%RELEASE_MANAGEMENT_AUTOMATION_URL%")
        param("GLOSSARY_COMPONENT_LINK", "%GLOSSARY_COMPONENT_URL%")
        param("SERVICE_DESK_LINK", "%SERVICE_DESK_URL%/secure/Dashboard.jspa")
        password("WIKI_PASSWORD", "%WIKI_RELENG_PASSWORD%")
    }

    steps {
        step {
            name = "Update TeamCity project and (or) build type parameter"
            id = "RUNNER_49"
            type = "UpdateTeamCityProjectsAndBuildConfigurationsParameter"
            executionMode = BuildStep.ExecutionMode.DEFAULT
            param("TEAMCITY_UPDATE_BUILD_CONFIGURATION_IDS", "%TEAMCITY_UPDATE_BUILD_CONFIGURATION_IDS%")
            param("TEAMCITY_UPDATE_PROJECT_IDS", "%TEAMCITY_UPDATE_PROJECT_IDS%")
            param("TEAMCITY_UPDATE_PARAMETER_NAME", "%TEAMCITY_UPDATE_PARAMETER_NAME%")
            param("TEAMCITY_UPDATE_PARAMETER_VALUE", "%TEAMCITY_UPDATE_PARAMETER_VALUE%")
            param("UPDATE_TEAMCITY_PARAMETERS_GRADLE_PARAMETERS", "--info --teamcityUrl=%TEAMCITY_URL% --teamcityUser=%TEAMCITY_REST_USER% --teamcityPassword=%TEAMCITY_REST_API_USER_PASSWORD% --parameterName=%TEAMCITY_UPDATE_PARAMETER_NAME% --parameterValue=%TEAMCITY_UPDATE_PARAMETER_VALUE%")
        }
        step {
            name = "Clone Component Sources"
            id = "RUNNER_862"
            type = "CloneGitRepository"
            executionMode = BuildStep.ExecutionMode.DEFAULT
            param("BRANCH", "v%BUILD_VERSION%")
            param("REUSE", "false")
            param("teamcity.step.phase", "")
            param("DIRECTORY", "octopus-%COMPONENT_NAME%")
            param("REPOSITORY_URL", "https://github.com/octopusden/octopus-%COMPONENT_NAME%.git")
        }
        gradle {
            name = "Publish documentation on wiki"
            id = "RUNNER_869"
            tasks = "adocPublishToWiki"
            buildFile = "build.gradle"
            workingDir = "octopus-%COMPONENT_NAME%"
            gradleParams = """-Pdocker.registry=%DOCKER_REGISTRY% -Pwiki.url=%WIKI_URL% -Pwiki.username=%WIKI_USER% -Pwiki.password=%WIKI_PASSWORD% -Pwiki.space-key=%WIKI_SPACE_KEY% -Pwiki.page-id=%WIKI_PAGE_ID% -Padoc.glossary-component-link=%GLOSSARY_COMPONENT_LINK% -Padoc.components-registry-link=%COMPONENTS_REGISTRY_LINK% -Padoc.release-management-automation-link=%RELEASE_MANAGEMENT_AUTOMATION_LINK% -Padoc.escrow-automation-tool-link=%ESCROW_AUTOMATION_TOOL_LINK% -Padoc.components-registry-validation-link=%COMPONENTS_REGISTRY_VALIDATION_LINK% -Padoc.service-desk-link=%SERVICE_DESK_LINK% "-Padoc.header=%WIKI_PAGE_HEADER%""""
            enableStacktrace = true
            jdkHome = "%env.JAVA_HOME%"
            jvmArgs = "-Duser.home=%teamcity.agent.jvm.user.home%"
            param("org.jfrog.artifactory.selectedDeployableServer.defaultModuleVersionConfiguration", "GLOBAL")
        }
        stepsOrder = arrayListOf("RUNNER_3344", "RUNNER_3529", "RUNNER_3370", "RUNNER_3371", "RUNNER_3373", "RUNNER_3530", "RUNNER_3544", "RUNNER_49", "RUNNER_2659", "RUNNER_3059", "RUNNER_862", "RUNNER_869")
    }

    dependencies {
        snapshot(id60DeployToOkdQaGhAuto) {
            onDependencyFailure = FailureAction.FAIL_TO_START
        }
    }
})

object WL_Validation_id : BuildType({
    templates(AbsoluteId("OctopusWlValidator"))
    name = "WL Validation"

    params {
        param("OCTOPUS_MODULE_NAME", "octopus-components-registry-service")
    }
})
