import static BuildSystem.MAVEN
import static VCS.CVS
import static org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode.AUTO

enum BuildSystem {
    BS2_0,
    MAVEN
}

enum VCS {
    CVS,
    MERCURIAL
}

final DEFAULT_TAG = '$module-$version'
final ANY_ARTIFACT = /[\w-]+/
final SOME_VERSION_RANGE = "[1.12.1-151,)"

Defaults {
    system = "NONE"
    releasesInDefaultBranch = true
    solution = false
    buildSystem = MAVEN
    repositoryType = CVS
    artifactId = ANY_ARTIFACT
    tag = DEFAULT_TAG
    escrow {
        generation = AUTO
    }
    build {
        requiredTools = "BuildEnv"
    }
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

    BuildLib {
        escrowEnvironmentVariable = "BUILD_LIB"
        targetLocation = "tools/BuildLib"
        sourceLocation = "env.BUILD_LIB"
    }
}


bcomponent {
    componentOwner = "user1"
    "$SOME_VERSION_RANGE" {
        vcsUrl = "ssh://hg@mercurial/bcomponent"
        groupId = "org.octopusden.octopus.bcomponent"
    }
    jira {
        projectKey = "BCOMPONENT"
    }
}
