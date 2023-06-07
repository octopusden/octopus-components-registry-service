import static BuildSystem.MAVEN
import static VCS.MERCURIAL

final DEFAULT_TAG = "$module-$version"
final ANY_ARTIFACT = /[\w-\.]+/

enum BuildSystem {
    BS2_0,
    MAVEN,
    PROVIDED
}

enum VCS {
    CVS,
    MERCURIAL
}


Defaults {
    repositoryType = MERCURIAL
    buildSystem = MAVEN;
    tag = DEFAULT_TAG;
    artifactId = ANY_ARTIFACT
}


bcomponent {
    componentOwner = "user1"
    "[1.12.1-150,)" {
        vcsUrl = "ssh://hg@mercurial/bcomponent"
        groupId = "org.octopusden.octopus.bcomponent"
        artifactId = "builder"
        jira {
            projectKey = "bs-core-jira-new"
            majorVersionFormat = '$major'
            releaseVersionFormat = '$major.$minor'
            buildVersionFormat = '$major.$minor.$build'
        }
    }
}

octopusweb {
    "(,0),[0,)" {
        groupId = "org.octopusden.octopus.octopusweb"
        artifactId = ANY_ARTIFACT
        buildSystem = BuildSystem.PROVIDED
        jira {
            projectKey = "WCOMPONENT"
            majorVersionFormat = '$major.$minor'
            releaseVersionFormat = '$major.$minor.$service'
            buildVersionFormat = '$major.$minor.$service.$build'
        }
    }
}
