import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL

Defaults {
    system = "NONE"
    repositoryType = MERCURIAL
    buildSystem = MAVEN
    tag = DEFAULT_TAG
}


Tools {
    BuildEnv {
        escrowEnvironmentVariable = "BUILD_ENV"
        targetLocation = "tools/BUILD_ENV"
        sourceLocation = "env.BUILD_ENV"
    }

    BuildLib {
        escrowEnvironmentVariable = "BUILD_LIB"
        targetLocation = "tools/BuildLib"
        sourceLocation = "env.BUILD_LIB"
    }

    AndroidSdk {
        escrowEnvironmentVariable = "AndroidSdk"
        targetLocation = "tools/AndroidSdk"
        sourceLocation = "env.AndroidSdk"
        installScript = "androidSdkInstaller"  //add .sh or .exe at installation
    }
}

