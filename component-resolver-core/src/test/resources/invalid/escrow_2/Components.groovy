import org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode
import static org.octopusden.octopus.escrow.BuildSystem.*


Component {
    componentDisplayName = "Component display name"
    componentOwner = "user5"
    releaseManager = "user5"
    securityChampion = "user5"
    jira {
        projectKey = "TTTT"
    }
    vcsSettings {
        tag = 'TTTT-R-$version'
        vcsUrl = 'ssh://hg@mercurial/o2/ts'
        branch = "default"
    }

    groupId = "org.octopusden.octopus.ts"
    artifactId = ANY_ARTIFACT

    distribution {
        explicit = false
        external = true
    }

    "[03.51.29.15,)" {
        buildSystem = WHISKEY
        build {
            requiredTools = "BuildEnv,Whiskey"
        }
        vcsSettings {
            externalRegistry = "dwh_db"
        }
    }
    "(,03.51.29.15)" {
        buildSystem = ESCROW_NOT_SUPPORTED
        escrow {
            generation = EscrowGenerationMode.UNSUPPORTED
        }
        vcsSettings {
            externalRegistry = "dwh_db"
        }
    }
}

test {
    componentOwner = "user"
    groupId = "org.octopusden.octopus.test"
    jira {
        projectKey = "DM"
        lineVersionFormat = '$major02.$minorC'
        majorVersionFormat = '$major02.$minorC.$serviceC'
        releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
        buildVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
        displayName = "Test DB"
        technical = true
        component {
            versionPrefix = 'db'
            versionFormat = '$baseVersionFormat-$versionPrefix'
        }
    }
    "[03.51.29.15,)" {
        buildSystem = WHISKEY
        escrow {
            generation = EscrowGenerationMode.AUTO
        }
        vcsSettings {
            externalRegistry = "test"
        }
    }
    "(,03.51.29.15)" {
        buildSystem = ESCROW_NOT_SUPPORTED
        escrow {
            generation = EscrowGenerationMode.UNSUPPORTED
        }
        vcsSettings {
            externalRegistry = "test"
        }
    }
    distribution {
        explicit = false
        external = true
    }
}
