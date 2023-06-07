package invalid

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

bcomponent {
    componentOwner = "user1"
    "1.0" {
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
