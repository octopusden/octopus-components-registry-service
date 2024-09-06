import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL


Defaults {
    system = "NONE"
    releasesInDefaultBranch = true
    solution = false
    repositoryType = MERCURIAL
    buildSystem = MAVEN;
}


bcomponent {
    componentOwner = "user1"
    "[1.12.1-150,)" {
        buildSystem = MAVEN
        repositoryType = MERCURIAL
        groupId = "org.octopusden.octopus.bcomponent"
        artifactId = "builder"
        tag = "$module.$version"
        branch = "default"
    }
    jira {
        projectKey = "BCOMPONENT"
    }
}
