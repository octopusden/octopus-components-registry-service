//import static BuildSystem.MAVEN
//import static VCS.MERCURIAL
import static org.octopusden.octopus.escrow.BuildSystem.BS2_0
import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL

DEFAULT_TAG = '$module-$version'


Defaults {
    system = "NONE"
    releasesInDefaultBranch = true
    solution = false
//    repositoryType = MERCURIAL
//    buildSystem = MAVEN;
//    versionRange = "(,)"
//    tag = DEFAULT_TAG;
    jira {
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
        customer {
            versionFormat = '$versionPrefix-$baseVersionFormat'
        }
    }
}

system {
    componentOwner = "user1"
    buildSystem = BS2_0;
    groupId = "org.octopusden.octopus.system"
    artifactId = "system"
    tag = '$module-$version'
    jira {
        projectKey = "SYSTEM"
    }
}

commoncomponent {
    componentOwner = "user1"
    buildSystem = MAVEN;
    repositoryType = MERCURIAL
    groupId = "org.octopusden.octopus.system"
    artifactId = "system"
    tag = '$module-$version'
    vcsUrl = "ssh://hg@mercurial/o2/other/system"
    jira {
        projectKey = "SYSTEM"
        component { versionPrefix = "common" }
    }
}
