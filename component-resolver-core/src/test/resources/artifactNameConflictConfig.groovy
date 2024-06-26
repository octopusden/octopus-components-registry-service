import static BuildSystem.MAVEN
import static VCS.MERCURIAL

DEFAULT_TAG = "$module-$version"

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
    repositoryType = MERCURIAL
    buildSystem = MAVEN;
    versionRange = "(,)"
    tag = DEFAULT_TAG;
    jira {
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
        customer {
            versionFormat = '$versionPrefix-$baseVersionFormat'
        }
    }
}

final String ALL_VERSIONS = "(,0),[0,)"

ptkmodel2 {
    componentOwner = "user1"
    "$ALL_VERSIONS" {
        groupId = "org.octopusden.octopus.system"
        artifactId = "ptkmodel2-parent|octopusumessagecontextxmltypes|ptkmodel2"
        vcsUrl = "ssh://hg@mercurial/o2/other/$module"
    }
    jira {
        projectKey = "SYSTEM"
    }
}

octopusumessage {
    componentOwner = "user1"
    "$ALL_VERSIONS" {
        groupId = "org.octopusden.octopus.system"
        artifactId = "octopusumessage"
        vcsUrl = "ssh://hg@mercurial/o2/other/octopusu-r2-message"
    }
    jira {
        projectKey = "SYSTEM"
    }
}
