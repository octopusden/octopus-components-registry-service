import static org.octopusden.octopus.escrow.BuildSystem.PROVIDED

Tools {
    TestTool1 {
        targetLocation = "tools/TEST_TOOL_1"
        sourceLocation = '$env.TEST_TOOL_1'
    }
    TestTool2 {
        escrowEnvironmentVariable = "TEST_TOOL_2"
    }
    TestTool3 { }
}

Defaults {
    system = "NONE"
    buildSystem = PROVIDED
    jira {
        projectKey = "COMPONENTDBM"
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
    }
}

TEST_COMPONENT_1 {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.test.build.configuration.tools"
    artifactId = "test-component1"
    solution = true
    releasesInDefaultBranch = false
    build {
        requiredTools = "TestTool1"
    }
}

TEST_COMPONENT_2 {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.test.build.configuration.tools"
    artifactId = "test-component2"
    solution = true
    releasesInDefaultBranch = false
    build {
        requiredTools = "TestTool2"
    }
}

TEST_COMPONENT_3 {
    componentOwner = "user9"
    groupId = "org.octopusden.octopus.test.build.configuration.tools"
    artifactId = "test-component3"
    solution = true
    releasesInDefaultBranch = false
    build {
        requiredTools = "TestTool3"
    }
}
