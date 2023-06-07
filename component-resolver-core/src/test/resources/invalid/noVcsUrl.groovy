package invalid

import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL

bcomponent {
    componentOwner = "user1"
    "(1.0,)" {
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
