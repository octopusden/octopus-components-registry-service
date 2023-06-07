package invalid

import static org.octopusden.octopus.escrow.BuildSystem.PROVIDED

octopusweb {
    "(,0),[0,)" {
        groupId = "org.octopusden.octopus.octopusweb"
        artifactId = "all"
        buildSystem = PROVIDED
        jiraProjectKey = "WCOMPONENT"
        jiraReleaseVersionFormat = '$major.$minor.$service'
    }
}

TEST_COMPONENT7 {
    "(2.4,)" {
        groupId = "org.octopusden.octopus.test_component7"
        artifactId = "test_component7"
        vcsUrl = "ssh://hg@mercurial//TEST_COMPONENT7/core"
        jira {
            projectKey = "TEST_COMPONENT7"
            majorVersionFormat = '$major'
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
            majorVersionFormat = '$major.$minor'
            releaseVersionFormat = '$major.$minor.$service'
        }
    }
}
