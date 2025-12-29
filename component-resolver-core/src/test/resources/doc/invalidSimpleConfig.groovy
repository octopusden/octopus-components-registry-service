import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL

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

component {
    componentOwner = "user"
    releaseManager = "user"
    securityChampion = "user"
    system = "CLASSIC"
    componentDisplayName = "COMPONENT Official Name"
    doc {
    }
    vcsUrl = "ssh://hg@mercurial/bcomponent"
    buildSystem = MAVEN
    repositoryType = MERCURIAL
    groupId = "io.bcomponent"
    artifactId = "builder"
    tag = '$module.$version'
    branch = "default"
    build {
        requiredTools = "BuildEnv"
    }
    distribution {
        explicit = true
        external = true
        GAV = "org.octopusden.octopus.bcomponent:builder:war,org.octopusden.octopus.bcomponent:builder:jar"
    }
    jira {
        projectKey = "BCOMPONENT"
    }
}
