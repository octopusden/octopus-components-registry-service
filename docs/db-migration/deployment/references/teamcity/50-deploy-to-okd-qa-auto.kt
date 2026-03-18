package _Self.buildTypes

import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.triggers.finishBuildTrigger

object id50DeployToOkdQaManual : BuildType({
    templates(AbsoluteId("RnDProcessesAutomation_IdpComponentOkdDeploy"))
    id("50DeployToOkdQaManual")
    name = "[5.0] Deploy to OKD QA [AUTO]"

    params {
        text("OKD_SERVER_URL", "%OKD_SERVER_DEV_URL%", allowEmpty = false)
        param("BUILD_NUMBER", "${id50ReleasePostProcessingAuto.depParamRefs.buildNumber}")
        text("OKD_APPS_DOMAIN", "%OKD_APPS_DOMAIN_DEV%", allowEmpty = false)
    }

    triggers {
        finishBuildTrigger {
            id = "TRIGGER_1596"
            buildType = "${id50ReleasePostProcessingAuto.id}"
        }
    }

    dependencies {
        snapshot(id50ReleasePostProcessingAuto) {
            onDependencyFailure = FailureAction.FAIL_TO_START
        }
    }

    disableSettings("BUILD_EXT_1740")
})
