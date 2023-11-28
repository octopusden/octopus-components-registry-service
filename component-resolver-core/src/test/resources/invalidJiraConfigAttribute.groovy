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
    system = "NONE"
    repositoryType = MERCURIAL
    buildSystem = MAVEN;
    tag = DEFAULT_TAG;
    artifactId = ANY_ARTIFACT
}


bcomponent {
    componentOwner = "user1"
    Mercurial {
        vcsUrl = "ssh://hg@mercurial/bcomponent"
        versionRange = "[1.12.1-150,)"
        groupId = "org.octopusden.octopus.bcomponent"
        artifactId = "builder"
        trolleybus = "210"
        jira {
            projectKey = "bs-core-jira-new"
            majorVersionFormat = '$major'
            releaseVersionFormat = '$major.$minor'
            unknownAttirubute = "zenit"
        }
    }
}
