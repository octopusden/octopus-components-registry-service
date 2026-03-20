package RDDepartment_Cdd.buildTypes

import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildFeatures.Swabra
import jetbrains.buildServer.configs.kotlin.buildFeatures.notifications
import jetbrains.buildServer.configs.kotlin.buildFeatures.swabra
import jetbrains.buildServer.configs.kotlin.buildSteps.script

object RnDProcessesAutomation_IdpComponentOkdDeploy : Template({
    id = AbsoluteId("RnDProcessesAutomation_IdpComponentOkdDeploy")
    name = "IDP Component OKD Deploy"

    buildNumberPattern = "%BUILD_NUMBER%"
    maxRunningBuilds = 1

    params {
        text("OKD_SERVER_URL", "%OKD_SERVER_DEV_URL%", allowEmpty = false)
        param("HELM_REPO_NAME", "helm-repo")
        param("DEPLOYMENT_CONFIG", "%DEPLOYMENT_CONFIG_DIR%/%COMPONENT_NAME%.yml")
        param("HELM_CHART_NAME", "spring-cloud")
        param("DEPLOYMENT_DEFAULT_CONFIG", "%DEPLOYMENT_CONFIG_DIR%/default.yml")
        param("DEPLOYMENT_ENVIRONMENT", "test")
        text("OKD_SA_TOKEN", "%OKD_SA_QA_TOKEN%", display = ParameterDisplay.HIDDEN, allowEmpty = true)
        text("OKD_APPS_DOMAIN", "%OKD_APPS_DOMAIN_DEV%", allowEmpty = false)
        password("OKD_SA_QA_TOKEN", "******")
        param("DEPLOYMENT_CONFIG_LABEL", "master")
        param("HELM_REPO_URL", "https://artifactory.%DOMAIN%/artifactory/helm")
        text("HELM_STANDARD_SERVICES_SET", "--set image.tag=%BUILD_NUMBER% --set componentName=%COMPONENT_NAME% --set configLabel=%DEPLOYMENT_CONFIG_LABEL% --set dockerRegistry=%DOCKER_REGISTRY% --set route.clusterDomain=%OKD_APPS_DOMAIN% --set additionalProfile=%F1_OKD_DEPLOYMENT_ADDITIONAL_PROFILE%", readOnly = true, allowEmpty = true)
        param("RENOVATE_SERVER_API", "https://renovate-server-f1.%OKD_APPS_DOMAIN%/")
        param("HELM_EXTRA_SERVICES_SET", "")
        param("HELM_RELEASE", "%COMPONENT_NAME%-%DEPLOYMENT_ENVIRONMENT%")
        param("OKD_PROJECT_NAME", "f1")
        text("DEPLOYMENT_CONFIG_DIR", "okd/deployments/%DEPLOYMENT_ENVIRONMENT%", readOnly = true, allowEmpty = false)
        password("OKD_SA_PROD_TOKEN", "******")
    }

    vcs {
        root(RnDProcessesAutomation_F1ServiceDeploymentOkd)

        cleanCheckout = true
        showDependenciesChanges = true
    }

    steps {
        script {
            name = "Login to the OKD cluster"
            id = "RUNNER_3344"
            scriptContent = """
                oc login --token=%OKD_SA_TOKEN% %OKD_SERVER_URL%
                oc project %OKD_PROJECT_NAME%
            """.trimIndent()
        }
        script {
            name = "Add Artifactory Helm Repo"
            id = "RUNNER_3529"
            scriptContent = """
                helm repo add %HELM_REPO_NAME% %HELM_REPO_URL%
                helm repo update
                helm search repo
            """.trimIndent()
        }
        script {
            name = "Helm analyse code for potential errors"
            id = "RUNNER_3370"
            scriptContent = """
                helm pull %HELM_REPO_NAME%/%HELM_CHART_NAME% --untar --untardir /tmp/chart/
                helm lint /tmp/chart/%HELM_CHART_NAME% -f %DEPLOYMENT_DEFAULT_CONFIG% -f %DEPLOYMENT_CONFIG%
            """.trimIndent()
        }
        script {
            name = "Helm simulate an installation"
            id = "RUNNER_3371"
            scriptContent = """
                helm upgrade %HELM_RELEASE% %HELM_REPO_NAME%/%HELM_CHART_NAME% \
                --atomic --install -n %OKD_PROJECT_NAME% %HELM_STANDARD_SERVICES_SET% %HELM_EXTRA_SERVICES_SET% -f %DEPLOYMENT_DEFAULT_CONFIG%,%DEPLOYMENT_CONFIG% \
                --dry-run
            """.trimIndent()
        }
        script {
            name = "Helm component deploy"
            id = "RUNNER_3373"
            scriptContent = """
                helm upgrade %HELM_RELEASE% %HELM_REPO_NAME%/%HELM_CHART_NAME% \
                --atomic --install --timeout 15m %HELM_STANDARD_SERVICES_SET% %HELM_EXTRA_SERVICES_SET% -f %DEPLOYMENT_DEFAULT_CONFIG%,%DEPLOYMENT_CONFIG% \
                -n %OKD_PROJECT_NAME%
            """.trimIndent()
        }
        script {
            name = "Unlogin from OKD cluster"
            id = "RUNNER_3530"
            executionMode = BuildStep.ExecutionMode.ALWAYS
            scriptContent = """
                oc logout
                rm -rf ~/.kube/
            """.trimIndent()
        }
        script {
            name = "Delete Helm chart archive from temp dir"
            id = "RUNNER_3544"
            executionMode = BuildStep.ExecutionMode.ALWAYS
            scriptContent = "rm -rf /tmp/chart/"
        }
        step {
            name = "Close Deployed Issues"
            id = "RUNNER_2659"
            type = "CloseDeployedIssues"

            conditions {
                equals("DEPLOYMENT_ENVIRONMENT", "production")
                equals("OCTOPUS_MODULE_NAME", "")
            }
            param("BUILD_VERSION", "%BUILD_NUMBER%")
            param("PATH_TO_POM_XML", "")
            param("MAVEN_CRM_PLUGIN_VERSION", "%MAVEN_CRM_PLUGIN_VERSION%")
            param("COMPONENT_NAME", "%COMPONENT_NAME%")
            param("EXTRA_PARAMETERS", "%RELENG_EXTRA_PARAMETERS%")
        }
        step {
            name = "Close Octopus deployment issue"
            id = "RUNNER_3059"
            type = "CloseJIRAIssue"

            conditions {
                equals("DEPLOYMENT_ENVIRONMENT", "production")
                doesNotEqual("OCTOPUS_MODULE_NAME", "")
            }
            param("PROJECT", "OCTOPUS")
            param("COMMENT", """"Issue has been deployed"""")
            param("JIRA_PASSWORD", "%JIRA_PLUGIN_UPDATER_PASSWORD%")
            param("ISSUE_TYPE", "Deployment")
            param("JIRA_URL", "%JIRA_URL%")
            param("JIRA_USER", "%JIRA_PLUGIN_UPDATER_USER%")
            param("VERSION", "%OCTOPUS_MODULE_NAME%-%BUILD_NUMBER%")
            param("COMPONENT_NAME", "%OCTOPUS_MODULE_NAME%")
        }
    }

    features {
        swabra {
            id = "swabra"
            filesCleanup = Swabra.FilesCleanup.AFTER_BUILD
        }
        notifications {
            id = "BUILD_EXT_1740"
            notifierSettings = emailNotifier {
                email = "%TEAMS_NOTIFICATION_CHANNEL%"
            }
            branchFilter = "+:<default>"
            buildFinishedSuccessfully = true
        }
    }

    requirements {
        contains("teamcity.agent.jvm.os.name", "Linux", "RQ_1751")
    }
})
