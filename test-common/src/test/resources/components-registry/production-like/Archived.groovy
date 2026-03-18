import static org.octopusden.octopus.escrow.BuildSystem.*
import static org.octopusden.octopus.escrow.RepositoryType.*
import org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode

// =============================================================================
// Archived components (~20) — various configurations marked archived=true
// =============================================================================

"archived-legacy-db" {
    componentDisplayName = "Legacy Database Module (archived)"
    archived = true
    componentOwner = "owner-d"
    groupId = "org.octopusden.octopus.legacy.db"
    artifactId = "legacy-db"

    jira {
        projectKey = "PROJ_D"
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/archive/legacy-db.git"
    }

    distribution {
        explicit = false
        external = true
    }
}

"archived-old-ui" {
    componentDisplayName = "Old UI Framework (archived)"
    archived = true
    componentOwner = "owner-g"
    groupId = "org.octopusden.octopus.legacy.ui"
    artifactId = "old-ui"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_H"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/archive/old-ui.git"
    }

    distribution {
        explicit = false
        external = true
    }
}

"archived-defunct-api" {
    componentDisplayName = "Defunct API Service (archived)"
    archived = true
    componentOwner = "owner-b"
    groupId = "org.octopusden.octopus.legacy.api"
    artifactId = "defunct-api"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_B"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/archive/defunct-api.git"
    }

    distribution {
        explicit = false
        external = true
        docker = "registry/defunct-api"
    }
}

"archived-retired-service" {
    componentDisplayName = "Retired Service (archived)"
    archived = true
    componentOwner = "owner-a"
    groupId = "org.octopusden.octopus.legacy.service"
    artifactId = "retired-service"

    jira {
        projectKey = "PROJ_A"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/archive/retired-service.git"
    }

    distribution {
        explicit = true
        external = true
        GAV = "org.octopusden.octopus.legacy.service:retired-service:zip"
    }
}

"archived-deprecated-lib" {
    componentDisplayName = "Deprecated Library (archived)"
    archived = true
    componentOwner = "owner-d"
    groupId = "org.octopusden.octopus.legacy.lib"
    artifactId = "deprecated-lib"

    jira {
        projectKey = "PROJ_D"
        component {
            versionPrefix = "deplib"
        }
    }

    distribution {
        explicit = false
        external = false
    }
}

"archived-obsolete-tool" {
    componentDisplayName = "Obsolete Build Tool (archived)"
    archived = true
    componentOwner = "owner-e"
    groupId = "org.octopusden.octopus.legacy.tool"
    artifactId = "obsolete-tool"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_E"
        technical = true
        displayName = "Obsolete Tool"
    }

    build {
        javaVersion = "1.8"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/archive/obsolete-tool.git"
    }

    distribution {
        explicit = false
        external = false
    }
}

"archived-classic-gateway" {
    componentDisplayName = "Classic Gateway (archived)"
    archived = true
    componentOwner = "owner-a"
    groupId = "org.octopusden.octopus.legacy.gateway"
    artifactId = "classic-gateway"

    jira {
        projectKey = "PROJ_A"
    }

    "(,1.0)" {
        jira {
            releaseVersionFormat = '$major.$minor.$service'
        }
    }

    "[1.0,)" {
        jira {
            releaseVersionFormat = '$major.$minor.$service-$fix'
        }
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/archive/classic-gateway.git"
    }

    distribution {
        explicit = true
        external = true
    }
}

"archived-old-sdk" {
    componentDisplayName = "Old SDK (archived)"
    archived = true
    componentOwner = "owner-g"
    groupId = "org.octopusden.octopus.legacy.sdk"
    artifactId = "old-sdk"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_H"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/archive/old-sdk.git"
    }

    build {
        buildTasks = "build -x test"
        systemProperties = "-Plegacy=true"
    }

    distribution {
        explicit = true
        external = false
    }
}

"archived-retired-adapter" {
    componentDisplayName = "Retired Protocol Adapter (archived)"
    archived = true
    componentOwner = "owner-d"
    groupId = "org.octopusden.octopus.legacy.adapter"
    artifactId = "retired-adapter"
    buildSystem = IN_CONTAINER

    jira {
        projectKey = "PROJ_D"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/archive/retired-adapter.git"
    }

    distribution {
        explicit = false
        external = true
    }
}

"archived-deprecated-widget" {
    componentDisplayName = "Deprecated Widget (archived)"
    archived = true
    componentOwner = "owner-g"
    groupId = "org.octopusden.octopus.legacy.widget"
    artifactId = "deprecated-widget"

    jira {
        projectKey = "PROJ_H"
    }

    distribution {
        explicit = false
        external = true
    }
}

"archived-legacy-auth" {
    componentDisplayName = "Legacy Auth Module (archived)"
    archived = true
    componentOwner = "owner-a"
    groupId = "org.octopusden.octopus.legacy.auth"
    artifactId = "legacy-auth"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_A"
        component {
            versionPrefix = "legauth"
        }
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/archive/legacy-auth.git"
    }

    distribution {
        explicit = false
        external = true
        docker = "registry/legacy-auth"
    }
}

"archived-old-protocol" {
    componentDisplayName = "Old Protocol Handler (archived)"
    archived = true
    componentOwner = "owner-d"
    groupId = "org.octopusden.octopus.legacy.protocol"
    artifactId = "old-protocol"

    jira {
        projectKey = "PROJ_D"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/archive/old-protocol.git"
    }

    distribution {
        explicit = false
        external = true
    }
}

"archived-defunct-logger" {
    componentDisplayName = "Defunct Logger (archived)"
    archived = true
    componentOwner = "owner-c"
    groupId = "org.octopusden.octopus.legacy.logger"
    artifactId = "defunct-logger"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_C"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/archive/defunct-logger.git"
    }

    distribution {
        explicit = false
        external = false
    }
}

"archived-retired-cache" {
    componentDisplayName = "Retired Cache Service (archived)"
    archived = true
    componentOwner = "owner-a"
    groupId = "org.octopusden.octopus.legacy.cache"
    artifactId = "retired-cache"

    jira {
        projectKey = "PROJ_A"
    }

    distribution {
        explicit = false
        external = true
    }
}

"archived-deprecated-config" {
    componentDisplayName = "Deprecated Config Service (archived)"
    archived = true
    componentOwner = "owner-c"
    groupId = "org.octopusden.octopus.legacy.config"
    artifactId = "deprecated-config"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_C"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/archive/deprecated-config.git"
    }

    distribution {
        explicit = false
        external = true
    }
}

"archived-aggregator" {
    componentOwner = "owner-d"
    componentDisplayName = "Archive Aggregator (archived)"
    archived = true
    groupId = "org.octopusden.octopus.legacy.aggr"
    buildSystem = ESCROW_NOT_SUPPORTED
    escrow {
        generation = EscrowGenerationMode.UNSUPPORTED
    }

    "(0,2)" {}

    jira {
        projectKey = "PROJ_D"
    }

    components {
        "archived-child-a" {
            componentOwner = "owner-d"
            componentDisplayName = "Archived Child A (archived)"
            archived = true
            vcsUrl = "ssh://git@git.example.com/archive/child-a.git"
            groupId = "org.octopusden.octopus.legacy.aggr"
            artifactId = "child-a"

            jira {
                displayName = "child-a"
                projectKey = "PROJ_D"
                majorVersionFormat = '$major'
                releaseVersionFormat = '$major.$minor'
            }

            distribution {
                explicit = false
                external = false
            }
        }

        "archived-child-b" {
            componentOwner = "owner-d"
            componentDisplayName = "Archived Child B (archived)"
            archived = true
            vcsUrl = "ssh://git@git.example.com/archive/child-b.git"
            groupId = "org.octopusden.octopus.legacy.aggr"
            artifactId = "child-b"

            jira {
                displayName = "child-b"
                projectKey = "PROJ_D"
                majorVersionFormat = '$major'
                releaseVersionFormat = '$major.$minor'
            }

            "[1.0,2.0)" {
                jira {
                    majorVersionFormat = '$major.$minor'
                    releaseVersionFormat = '$major.$minor.$service'
                }
            }

            distribution {
                explicit = false
                external = false
            }
        }
    }
}

"archived-manual-delivery" {
    componentDisplayName = "Manual Delivery Package (archived)"
    archived = true
    clientCode = "MANUAL_DLV"
    componentOwner = "owner-f"
    groupId = "org.octopusden.octopus.legacy.manual"
    artifactId = "manual-delivery"

    jira {
        projectKey = "PROJ_F"
        customer {
            versionPrefix = "manual"
        }
    }

    buildSystem = ESCROW_PROVIDED_MANUALLY
    escrow {
        generation = EscrowGenerationMode.MANUAL
    }

    distribution {
        explicit = true
        external = true
    }
}

"archived-legacy-client" {
    componentDisplayName = "Legacy Client App (archived)"
    archived = true
    clientCode = "LEGACY_CLIENT"
    componentOwner = "owner-g"
    groupId = "org.octopusden.octopus.legacy.client"
    artifactId = "legacy-client"

    jira {
        projectKey = "PROJ_H"
        customer {
            versionPrefix = "legclient"
        }
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/archive/legacy-client.git"
    }

    distribution {
        explicit = true
        external = true
        GAV = "org.octopusden.octopus.legacy.client:legacy-client:zip"
    }
}
