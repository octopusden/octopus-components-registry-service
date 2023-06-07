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
    }
}
