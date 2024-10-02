Tools {
    BuildEnv {
        escrowEnvironmentVariable = "BUILD_ENV"
        targetLocation = "tools/BUILD_ENV"
        sourceLocation = '$env.BUILD_ENV'
    }

    PowerBuilderCompiler170 {
        escrowEnvironmentVariable = "PBC_BIN"
        targetLocation = "tools/auto_compiler"
        sourceLocation = '$env.PBC/170'
    }
}
