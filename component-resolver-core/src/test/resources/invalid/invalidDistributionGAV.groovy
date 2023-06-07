package invalid

import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL

bcomponent {
    componentOwner = "user"
    componentDisplayName = "BCOMPONENT Official Name"
    vcsUrl = "ssh://hg@mercurial/bcomponent"
    buildSystem = MAVEN
    repositoryType = MERCURIAL
    groupId = "org.octopusden.octopus.bcomponent"
    artifactId = "builder"
    tag = '$module.$version'
    branch = "default"
    distribution {
        explicit = true
        external = true
        GAV = "org.octopusden.octopus.bcomponent:build/er:war,org.octopusden.octopus.bcomponent:builder:jar"
    }
    jira {
        projectKey = "BCOMPONENT"
    }
}
