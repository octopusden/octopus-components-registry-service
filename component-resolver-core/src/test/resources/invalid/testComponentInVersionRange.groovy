import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL

Defaults {
    system = "NONE"
    releasesInDefaultBranch = true
    solution = false
    jira {
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
    }
}

bcomponent {
    componentOwner = "user"
    releaseManager = "user"
    securityChampion = "user"
    system = "CLASSIC"
    vcsUrl = "ssh://hg@mercurial/bcomponent"
    buildSystem = MAVEN
    repositoryType = MERCURIAL
    groupId = "io.bcomponent"
    artifactId = "builder"
    tag = '$module.$version'
    branch = "default"
    jira {
        projectKey = "BCOMPONENT"
    }
    "[1.12.1-150,)" {
        testComponent = true
    }
}
