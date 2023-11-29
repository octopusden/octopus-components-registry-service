import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL

Defaults {
    system = "NONE"
    jira {
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
        customer {
            versionFormat = '$versionPrefix-$baseVersionFormat'
        }
    }
}

Tools {
    BuildEnv {
        escrowEnvironmentVariable = "BUILD_ENV"
        targetLocation = "tools/BUILD_ENV"
        sourceLocation = "env.BUILD_ENV"
        installScript = "script"
    }

}

bcomponent {
    componentOwner = "user"
    releaseManager = "user"
    securityChampion = "user"
    system = "CLASSIC"
    componentDisplayName = "BCOMPONENT Official Name"
    "[1.12.1-150,)" {
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
}
