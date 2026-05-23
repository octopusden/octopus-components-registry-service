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

    buildType(id10CompileUtAuto)
    buildType(id15CompatManual)
    buildType(id16CompatTraceReplayManual)
    buildType(id17CompatLocalStandManual)
    buildType(id20ValidateComponentsRegistryProductionDataAuto)
    buildType(id30DeployToOkdQaDevAuto)
    buildType(id40ReleaseManual)
    buildType(id50ReleasePostProcessingAuto)
    buildType(id60DeployToOkdQaGhAuto)
    buildType(id70DeployToOkdProdManual_2)
    buildType(WL_Validation_id)

    buildTypesOrder = arrayListOf(
        id10CompileUtAuto,
        id15CompatManual,
        id16CompatTraceReplayManual,
        id17CompatLocalStandManual,
        id20ValidateComponentsRegistryProductionDataAuto,
        id30DeployToOkdQaDevAuto,
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

// Compatibility Test — manual, on-demand. Runs the compat-test module against
// a baseline (production / main) and candidate (v3 stand) deployment pair via
// HTTP URLs (no JAR launch on the agent).
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
    name = "[1.5] Compatibility Test [MANUAL]"

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
})

// Compatibility Trace Replay — manual, on-demand. Replays the deduplicated
// production HTTP-traffic dump from the internal `crs-compat-trace` repo
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
    name = "[1.6] Compatibility Trace Replay [MANUAL]"

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
        param("GRADLE_TASK", ":components-registry-compat-test:test --tests *TraceReplayCompatTest* :components-registry-compat-test:compatibilityReporter")
        param("GRADLE_EXTRA_PARAMETERS", """
            -Pcompat.baseline.url=%COMPAT_BASELINE_URL%
            -Pcompat.candidate.url=%COMPAT_CANDIDATE_URL%
            -Pcompat.rms.url=%COMPAT_RMS_URL%
            -Pcompat.allow-non-db-candidate=%COMPAT_ALLOW_NON_DB_CANDIDATE%
            -Pcompat.trace.file=%teamcity.build.checkoutDir%/trace-data/%COMPAT_TRACE_FILE_RELATIVE%
            -Pcompat.parallelism=10
        """.trimIndent())
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
})

// Compatibility Local-Stand — manual-only with a snapshot dep on id10
// (snapshot only, NOT a `finishBuildTrigger` — operator clicks Run and
// id10 is pulled in transitively if needed; the build does not
// auto-fire when id10 finishes). Spins TWO CRS instances side-by-side
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
    name = "[1.7] Compatibility Local-Stand [MANUAL]"

    artifactRules = """
        **/build/reports/** => reports
        **/build/test-results/**/*.xml => test-results
        /tmp/crs-id17-%teamcity.build.id%/baseline.log => logs/baseline.log
        /tmp/crs-id17-%teamcity.build.id%/candidate.log => logs/candidate.log
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
        // starting with `#` are ignored. Pick `components/smoke.txt` for a
        // ~5-component fast run, `components/extended.txt` for ~50, or
        // `components/all.txt` for the full sweep.
        text("COMPAT_COMPONENTS_FILE", "components/smoke.txt", allowEmpty = false, display = ParameterDisplay.PROMPT)
        text("COMPAT_FULL", "false", allowEmpty = false, display = ParameterDisplay.PROMPT)
        // Baseline version is the project-level `LAST_RELEASE_VERSION` (e.g.
        // `2.0.87`); pinned, not prompted — operator updates the project
        // param when a new release lands.
        param("COMPAT_BASELINE_VERSION", "%LAST_RELEASE_VERSION%")
        // Candidate image tag = the build number of the upstream id10 chain
        // step that just pushed it via `dockerPushImage`.
        param("COMPAT_CANDIDATE_VERSION", "${id10CompileUtAuto.depParamRefs.buildNumber}")
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
            // `%teamcity.build.id%` substitution. Ports stay at the
            // wrapper defaults (4567/4568) — TC's per-config serialization
            // is sufficient guard against the same id17 running twice in
            // parallel on one agent, and we don't expect multiple
            // local-stand configs to coexist.
            scriptContent = """
                set -euo pipefail
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
                export COMPAT_PARALLELISM=8
                export RESET_DB=1
                export COMPONENTS_REGISTRY_SERVICE_VERSION="%COMPAT_BASELINE_VERSION%"
                export BUILD_VERSION="%COMPAT_CANDIDATE_VERSION%"
                bash scripts/local-stands/teamcity-run.sh
            """.trimIndent()
        }
    }

    failureConditions {
        // Full sweep budget. The 5-component smoke completes in ~5-10 min;
        // a full ~475-component matrix needs ~15-30 min. Padded to 60 min
        // for cold-image-pull + first-time postgres volume init.
        executionTimeoutMin = 60
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
