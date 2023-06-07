import static org.octopusden.octopus.escrow.BuildSystem.PROVIDED


component {
    componentOwner = "user1"
    "(,0),[0,)" {
        groupId = "org.octopusden.octopus.componentc"
        artifactId = "test_component6"
        buildSystem = PROVIDED
        jira {
            projectKey = "TEST_COMPONENT2"
            majorVersionFormat = '$major'
            releaseVersionFormat = '$major.$minor.$service'
            customer {
                versionPrefix = "TEST_COMPONENT2"
            }
            component {
                versionPrefix = "TEST_COMPONENT2"
            }
        }
    }
}
