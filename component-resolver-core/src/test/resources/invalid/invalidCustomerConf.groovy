package invalid

import static org.octopusden.octopus.escrow.BuildSystem.PROVIDED

componentp {
    "(2.4,)" {
        groupId = "org.octopusden.octopus.componentp"
        artifactId = "componentps"
        vcsUrl = "ssh://hg@mercurial//componentp/core"
        jira {
            projectKey = "componentp"
            majorVersionFormat = '$major'
            releaseVersionFormat = '$major.$minor.$service'
            displayName = "CLIENT"
            customer {
            }
        }
    }
}


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
        }
    }
}
