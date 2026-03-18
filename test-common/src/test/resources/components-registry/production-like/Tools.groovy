Tools {
    BuildEnv {
        escrowEnvironmentVariable = "BUILD_ENV"
        targetLocation = "tools/BUILD_ENV"
        sourceLocation = '$env.BUILD_ENV'
    }

    AndroidSdk {
        escrowEnvironmentVariable = "ANDROID_SDK"
        targetLocation = "tools/ANDROID_SDK"
        sourceLocation = '$env.ANDROID_SDK'
    }
}
