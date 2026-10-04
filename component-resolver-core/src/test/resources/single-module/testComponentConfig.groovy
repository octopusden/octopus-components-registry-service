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
    testComponent = true
    vcsUrl = "ssh://hg@mercurial/bcomponent"
    buildSystem = MAVEN
    repositoryType = MERCURIAL
    groupId = "io.bcomponent"
    tag = '$module.$version'
    branch = "default"
    jira {
        projectKey = "BCOMPONENT"
    }
    "(,1.12.1-150)" {
        artifactId = "builder-old"
    }
    "[1.12.1-150,)" {
        artifactId = "builder"
    }
}

ccomponent {
    componentOwner = "user"
    releaseManager = "user"
    securityChampion = "user"
    system = "CLASSIC"
    vcsUrl = "ssh://hg@mercurial/ccomponent"
    buildSystem = MAVEN
    repositoryType = MERCURIAL
    groupId = "org.octopusden.octopus.ccomponent"
    artifactId = "ccomponent"
    tag = '$module.$version'
    branch = "default"
    jira {
        projectKey = "CCOMPONENT"
    }
}
