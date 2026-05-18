import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildFeatures.XmlReport
import jetbrains.buildServer.configs.kotlin.buildFeatures.swabra
import jetbrains.buildServer.configs.kotlin.buildFeatures.xmlReport
import jetbrains.buildServer.configs.kotlin.buildSteps.gradle
import jetbrains.buildServer.configs.kotlin.buildSteps.kotlinFile
import jetbrains.buildServer.configs.kotlin.buildSteps.script
import jetbrains.buildServer.configs.kotlin.triggers.finishBuildTrigger
import jetbrains.buildServer.configs.kotlin.vcs.GitVcsRoot

version = "2025.03"

project {
    vcsRoot(OctopusComponentsRegistryServiceVcs)
    vcsRoot(ComponentsRegistry)

    params {
        param("COMPONENT_NAME", "components-registry-service")
        param("OCTOPUS_MODULE_NAME", "octopus-components-registry-service")
        param("OKD_IMAGE_NAME", "components-registry-service")
        param("LAST_RELEASE_VERSION", "0.0.1")
        param("PROJECT_VERSION", "0.0.1")
    }

    buildType(id10CompileUtAuto)
    buildType(id15CompatManual)
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
        id20ValidateComponentsRegistryProductionDataAuto,
        id30DeployToOkdQaDevAuto,
        id40ReleaseManual,
        id50ReleasePostProcessingAuto,
        id60DeployToOkdQaGhAuto,
        id70DeployToOkdProdManual_2,
        WL_Validation_id
    )
}

// Primary VCS root for the v3 branch line.
// The id below must match the existing TC-side VCS root id so import
// re-attaches to the same root with its history and credentials intact.
// If the live TC export shows a different id, replace this string with
// the export value before merging.
object OctopusComponentsRegistryServiceVcs : GitVcsRoot({
    id("OctopusComponentsRegistryServiceVcs")
    name = "octopus-components-registry-service"
    url = "https://github.com/octopusden/octopus-components-registry-service.git"
    branch = "refs/heads/v3"
    branchSpec = "+:refs/heads/*"
    authMethod = password {
        userName = "%github.user%"
        password = "%github.token%"
    }
})

// Secondary VCS root used by id20ValidateComponentsRegistryProductionDataAuto
// to check out the downstream Components-Registry config under
// %COMPONENTS_REGISTRY_CHECKOUT_DIR%. The original definition lives in the
// live TC project's _Self/vcsRoots/ subtree; this stub keeps the DSL
// compilable. Replace url/branch/auth with the live values from the TC
// export before merge so import re-attaches to the same root.
object ComponentsRegistry : GitVcsRoot({
    id("ComponentsRegistry")
    name = "ComponentsRegistry"
    url = "%COMPONENTS_REGISTRY_REPO_URL%"
    branch = "refs/heads/master"
    branchSpec = "+:refs/heads/*"
    authMethod = password {
        userName = "%bitbucket.user%"
        password = "%bitbucket.password%"
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
            name = "Gradle Build & UT (1)"
            id = "RUNNER_1319"
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
        stepsOrder = arrayListOf("Login_to_the_OKD_cluster", "RUNNER_1720", "RUNNER_1768", "RUNNER_1319")
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
// a baseline (production / main) and candidate (v3 stand) deployment pair.
// All four COMPAT_* params are prompted on every run; without them the
// task either skips silently (smoke list empty) or reports only env
// preconditions, producing misleading green builds.
object id15CompatManual : BuildType({
    templates(AbsoluteId("Octopus_OctopusGradleBuild"))
    id("15CompatManual")
    name = "[1.5] Compatibility Test [MANUAL]"

    artifactRules = """
        %ARTIFACT_PATH%
    """.trimIndent()

    params {
        text("COMPAT_BASELINE_URL", "", allowEmpty = false)
        text("COMPAT_CANDIDATE_URL", "", allowEmpty = false)
        text("COMPAT_RMS_URL", "", allowEmpty = false)
        text("COMPAT_SMOKE_COMPONENTS", "", allowEmpty = false)
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
        text("OKD_SERVER_URL", "%OKD_SERVER_DEV_URL%", allowEmpty = false)
        param("TEAMS_NOTIFICATION_CHANNEL", "")
        param("BUILD_NUMBER", "${id10CompileUtAuto.depParamRefs.buildNumber}")
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
            name = "Call GitHub Release (Kotlin) (1)"
            id = "RUNNER_1326"
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
        text("OKD_SERVER_URL", "%OKD_SERVER_DEV_URL%", allowEmpty = false)
        param("TEAMS_NOTIFICATION_CHANNEL", "")
        param("BUILD_NUMBER", "${id50ReleasePostProcessingAuto.depParamRefs.buildNumber}")
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
        text("OKD_SERVER_URL", "%OKD_SERVER_PROD_URL%", allowEmpty = false)
        param("BUILD_VERSION", "%BUILD_NUMBER%")
        param("ESCROW_AUTOMATION_TOOL_LINK", "%ESCROW_AUTOMATION_TOOL_URL%")
        param("TEAMCITY_UPDATE_PARAMETER_NAME", "COMPONENTS_REGISTRY_SERVICE_VERSION")
        param("WIKI_USER", "%COMPONENTS_REGISTRY_WIKI_USER%")
        param("WIKI_PAGE_HEADER", "%COMPONENTS_REGISTRY_WIKI_PAGE_HEADER%")
        param("WIKI_SPACE_KEY", "%COMPONENTS_REGISTRY_WIKI_SPACE_KEY%")
        param("WIKI_PAGE_ID", "%COMPONENTS_REGISTRY_WIKI_PAGE_ID%")
        param("DEPLOYMENT_ENVIRONMENT", "production")
        param("COMPONENTS_REGISTRY_VALIDATION_LINK", "%COMPONENTS_REGISTRY_VALIDATION_URL%")
        param("TEAMCITY_UPDATE_PARAMETER_VALUE", "%BUILD_NUMBER%")
        text("OKD_SA_TOKEN", "%OKD_SA_PROD_TOKEN%", display = ParameterDisplay.HIDDEN, allowEmpty = true)
        param("COMPONENTS_REGISTRY_LINK", "%COMPONENTS_REGISTRY_BROWSE_URL%")
        param("BUILD_NUMBER", "${id60DeployToOkdQaGhAuto.depParamRefs["BUILD_NUMBER"]}")
        param("RELEASE_MANAGEMENT_AUTOMATION_LINK", "%RELEASE_MANAGEMENT_AUTOMATION_URL%")
        param("GLOSSARY_COMPONENT_LINK", "%GLOSSARY_COMPONENT_URL%")
        param("SERVICE_DESK_LINK", "%SERVICE_DESK_URL%/secure/Dashboard.jspa")
        password("WIKI_PASSWORD", "******")
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
})
