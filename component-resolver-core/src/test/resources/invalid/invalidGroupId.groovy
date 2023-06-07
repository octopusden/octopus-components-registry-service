package invalid

import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL

bcomponent {
    componentOwner = "user1"
    "[1.0]" {
        vcsUrl = "ssh://hg@mercurial/bcomponent"
        buildSystem = MAVEN
        repositoryType = MERCURIAL
        artifactId = "builder"
        tag = "$module.$version"
    }
    jira {
        projectKey = "BCOMPONENT"
    }
}
