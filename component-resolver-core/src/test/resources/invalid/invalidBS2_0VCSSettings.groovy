import static BuildSystem.BS2_0
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

componentWithVCSUrl {
    buildSystem = BS2_0
    groupId = "org.octopusden.octopus.mygroupId1"
    artifactId = "myartifact1"
    vcsSettings {
        tag = '$module.$version'
        vcsUrl = "ssh://hg@mercurial/bcomponent"
        repositoryType = MERCURIAL
    }
}


componentWithoutTag {
    buildSystem = BS2_0
    groupId = "org.octopusden.octopus.mygroupId2"
    artifactId = "myartifact2"
}

componentWith2VCSRoots {
    buildSystem = BS2_0
    groupId = "org.octopusden.octopus.mygroupId3"
    artifactId = "myartifact3"
    vcsSettings {
        mercurial1 {
            tag = '$module.$version'
            vcsUrl = "ssh://hg@mercurial/bcomponent"
            repositoryType = MERCURIAL
        }
        mercurial2 {
            tag = '$module.$version'
            vcsUrl = "ssh://hg@mercurial/zenit"
            repositoryType = MERCURIAL
        }
    }
}



