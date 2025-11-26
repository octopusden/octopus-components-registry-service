"test-component" {
    componentOwner = "test-user"
    groupId = "org.octopusden.octopus.test"
    artifactId = "test-artifact"
    jira {
        projectKey = "TEST"
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
    }
}
