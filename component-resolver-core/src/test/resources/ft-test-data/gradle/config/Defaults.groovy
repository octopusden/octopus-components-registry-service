import static org.octopusden.octopus.escrow.BuildSystem.*
import static org.octopusden.octopus.escrow.RepositoryType.*


Defaults {
    repositoryType = MERCURIAL
    buildSystem = MAVEN;
    tag = '$module-$version';
    artifactId = ANY_ARTIFACT
    build {
        requiredTools = "BuildEnv"
        javaVersion = "1.8"
        mavenVersion = "LATEST"
        gradleVersion = "LATEST"
    }
}

Tools {
    BuildEnv {
        escrowEnvironmentVariable = "BUILD_ENV"
        targetLocation = "tools/BUILD_ENV"
        sourceLocation = '$env.BUILD_ENV_TEST'
    }

    BuildLib {
        escrowEnvironmentVariable = "BUILD_LIB"
        targetLocation = "tools/BuildLib"
        sourceLocation = '$env.BUILD_LIB'
    }

    AndroidSdk {
        escrowEnvironmentVariable = "AndroidSdk"
        targetLocation = "tools/AndroidSdk"
        sourceLocation = '$env.AndroidSdk'
        installScript = "androidSdkInstaller"  //add .sh or .exe at installation
    }
}
