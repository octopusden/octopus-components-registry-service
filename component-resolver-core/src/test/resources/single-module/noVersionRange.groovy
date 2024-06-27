import static BuildSystem.MAVEN
import static VCS.MERCURIAL

enum BuildSystem {
    BS2_0,
    MAVEN
}

enum VCS {
    CVS,
    MERCURIAL
}

Defaults {
    system = "NONE"
    releasesInDefaultBranch = true
    solution = false
    jira {
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
        customer {
            versionFormat = '$versionPrefix-$baseVersionFormat'
        }
    }
}

final ALL_VERSIONS = "(,0),[0,)"

bcomponent {
    componentOwner = "user1"
    "${ALL_VERSIONS}" {
        vcsUrl = "ssh://hg@mercurial/bcomponent"
        buildSystem = MAVEN
        repositoryType = MERCURIAL
        groupId = "org.octopusden.octopus.bcomponent"
        artifactId = "builder"
        tag = "$module.$version"
    }
    jira {
        projectKey = "BCOMPONENT"
    }
}
