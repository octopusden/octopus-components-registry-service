import static org.octopusden.octopus.escrow.BuildSystem.*
import static org.octopusden.octopus.escrow.RepositoryType.*

"test-component" {
    componentOwner = "test-user"
    groupId = "org.octopusden.octopus.test"
    artifactId = "test-artifact"
    buildSystem = GRADLE
    vcsSettings {
        vcsUrl = "ssh://git@myserver/testprj/test-component.git"
    }
    jira {
        projectKey = "TEST"
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
    }
}
