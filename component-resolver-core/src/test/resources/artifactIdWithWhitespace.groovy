import static BuildSystem.MAVEN
import static VCS.MERCURIAL

final DEFAULT_TAG = "$module-$version"
final ANY_ARTIFACT = /[\w-\.]+/

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
    repositoryType = MERCURIAL
    buildSystem = MAVEN;
    tag = DEFAULT_TAG;
    artifactId = ANY_ARTIFACT
    jira {
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
        customer {
            versionFormat = '$versionPrefix-$baseVersionFormat'
        }
    }
}


"jar-osgifier" {
    componentOwner = "user1"
    "(,0),[0,)" {
        groupId = "org.octopusden.octopus.buildsystem"
        artifactId = "jar-osgifier "
        vcsUrl = "ssh://hg@mercurial//jar-osgifier"
    }
    jira {
        projectKey = "SYSTEM"
    }
}

