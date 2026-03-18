import static org.octopusden.octopus.escrow.BuildSystem.*
import static org.octopusden.octopus.escrow.RepositoryType.*
import org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode

// =============================================================================
// Group 1: GRADLE components — various configurations
// =============================================================================

"payment-gateway" {
    componentDisplayName = "Payment Gateway Service"
    componentOwner = "owner-a"
    releaseManager = "manager-a"
    securityChampion = "champion-a"
    groupId = "org.octopusden.octopus.payment"
    artifactId = "payment-gateway"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_A"
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
    }

    build {
        javaVersion = "17"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/payment-gateway.git"
    }

    distribution {
        explicit = true
        external = true
        GAV = "org.octopusden.octopus.payment:payment-gateway:zip"
        docker = "registry/payment-gateway"
        securityGroups {
            read = "team-a#payment-rd"
        }
    }
}

"auth-service" {
    componentDisplayName = "Authentication Service"
    componentOwner = "owner-a"
    groupId = "org.octopusden.octopus.auth"
    artifactId = "auth-service"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_A"
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
        component {
            versionPrefix = "auth"
            versionFormat = '$versionPrefix.$baseVersionFormat'
        }
    }

    build {
        javaVersion = "17"
        requiredProject = false
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/auth-service.git"
        tag = 'auth-service-$version'
    }

    distribution {
        explicit = false
        external = true
    }
}

"notification-service" {
    componentDisplayName = "Notification Service"
    componentOwner = "owner-b"
    groupId = "org.octopusden.octopus.notification"
    artifactId = "notification-service"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_B"
        majorVersionFormat = '$major'
        releaseVersionFormat = '$major.$minor.$service'
        component {
            versionPrefix = "NotifSvc"
            versionFormat = '$versionPrefix.$baseVersionFormat'
        }
    }

    build {
        javaVersion = "17"
        requiredProject = false
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/notification-service.git"
        tag = 'notification-$version'
    }

    distribution {
        explicit = false
        external = true
        docker = "registry/notification-service"
    }
}

"user-management" {
    componentDisplayName = "User Management Module"
    componentOwner = "owner-a"
    groupId = "org.octopusden.octopus.usermgmt"
    artifactId = "user-management"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_A"
        component {
            versionPrefix = "usermgmt"
            versionFormat = '$versionPrefix.$baseVersionFormat'
        }
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/user-management.git"
    }

    distribution {
        explicit = true
        external = true
        GAV = "org.octopusden.octopus.usermgmt:user-management:zip"
    }
}

"api-gateway" {
    componentDisplayName = "API Gateway"
    componentOwner = "owner-b"
    groupId = "org.octopusden.octopus.apigateway"
    artifactId = "api-gateway-service"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_B"
        component {
            versionPrefix = "ApiGw"
            versionFormat = '$versionPrefix.$baseVersionFormat'
        }
    }

    build {
        javaVersion = "17"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/api-gateway.git"
        tag = 'api-gateway-$version'
        branch = 'main|v$major.$minor'
    }

    distribution {
        explicit = true
        external = true
        GAV = "org.octopusden.octopus.apigateway:api-gateway-service:zip:appserv"
        docker = "registry/api-gateway"
    }
}

"config-service" {
    componentDisplayName = "Configuration Service"
    componentOwner = "owner-c"
    groupId = "org.octopusden.octopus.config"
    artifactId = "config-service"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_C"
        majorVersionFormat = '$major'
        releaseVersionFormat = '$major.$minor.$service'
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/config-service.git"
        branch = 'master|$major.$minor'
    }

    distribution {
        explicit = false
        external = true
        docker = "registry/config-service"
    }
}

"discovery-service" {
    componentDisplayName = "Service Discovery"
    componentOwner = "owner-c"
    groupId = "org.octopusden.octopus.discovery"
    artifactId = "discovery-service"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_C"
        component {
            versionPrefix = "discovery"
            versionFormat = '$versionPrefix.$baseVersionFormat'
        }
    }

    build {
        javaVersion = "17"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/discovery-service.git"
        tag = 'discovery-$version'
        hotfixBranch = 'hotfix/$major.$minor.$service'
    }

    distribution {
        explicit = false
        external = true
    }
}

"logging-service" {
    componentDisplayName = "Logging Service"
    componentOwner = "owner-c"
    groupId = "org.octopusden.octopus.logging"
    artifactId = "logging-core,logging-appender,logging-api"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_C"
        component {
            versionPrefix = "logging"
        }
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/logging-service.git"
        tag = 'logging-$version'
    }

    distribution {
        explicit = false
        external = true
    }
}

"metrics-collector" {
    componentDisplayName = "Metrics Collector"
    componentOwner = "owner-b"
    groupId = "org.octopusden.octopus.metrics"
    artifactId = "metrics-collector"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_B"
    }

    build {
        javaVersion = "11"
        buildTasks = "build -x test"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/metrics-collector.git"
    }

    distribution {
        explicit = false
        external = true
    }
}

"cache-service" {
    componentDisplayName = "Distributed Cache Service"
    componentOwner = "owner-a"
    groupId = "org.octopusden.octopus.cache"
    artifactId = "cache-service"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_A"
        component {
            versionPrefix = "cache"
            versionFormat = '$versionPrefix.$baseVersionFormat'
        }
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/cache-service.git"
        tag = 'cache-$version'
    }

    "(,2.0)" {
        vcsSettings {
            branch = "v1"
        }
        build {
            javaVersion = "1.8"
        }
    }

    "[2.0,)" {
        vcsSettings {
            branch = "main"
        }
        build {
            javaVersion = "17"
        }
    }

    distribution {
        explicit = false
        external = true
        docker = "registry/cache-service"
    }
}

// =============================================================================
// Group 2: MAVEN components
// =============================================================================

"db-kernel" {
    componentDisplayName = "Database Kernel"
    componentOwner = "owner-d"
    groupId = "org.octopusden.octopus.dbkernel"
    artifactId = "db-kernel"

    jira {
        projectKey = "PROJ_D"
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/db-kernel.git"
    }

    distribution {
        explicit = false
        external = true
    }
}

"crypto-lib" {
    componentDisplayName = "Cryptography Library"
    componentOwner = "owner-d"
    groupId = "org.octopusden.octopus.crypto"
    artifactId = "crypto-lib"

    jira {
        projectKey = "PROJ_D"
        component {
            versionPrefix = "crypto"
        }
    }

    build {
        javaVersion = "11"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/crypto-lib.git"
    }

    distribution {
        explicit = false
        external = false
    }
}

"protocol-adapter" {
    componentDisplayName = "Protocol Adapter"
    componentOwner = "owner-d"
    groupId = "org.octopusden.octopus.protocol"
    artifactId = "protocol-adapter"
    tag = 'adapter-$version'

    jira {
        projectKey = "PROJ_D"
        majorVersionFormat = '$major'
        releaseVersionFormat = '$major.$minor.$service'
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/protocol-adapter.git"
    }

    distribution {
        explicit = true
        external = true
        GAV = "org.octopusden.octopus.protocol:protocol-adapter:tgz"
    }
}

"platform-commons" {
    componentDisplayName = "Platform Commons"
    componentOwner = "owner-d"
    groupId = "org.octopusden.octopus.platform"
    artifactId = "platform-commons-core,platform-commons-logging,platform-commons-http,platform-commons-xml,platform-commons-cli"
    tag = 'platform-commons-$version'

    jira {
        projectKey = "PROJ_E"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/platform-commons.git"
        branch = 'master|$major.$minor'
    }

    "[1.0,1.0.336)" {
        build {
            buildTasks = "assemble -x checkstyleMain"
        }
    }

    "[1.0.336,)" {}

    distribution {
        explicit = false
        external = true
    }
}

"xml-parser" {
    componentDisplayName = "XML Parser Library"
    componentOwner = "owner-d"
    groupId = "org.octopusden.octopus.xml"
    artifactId = "xml-parser"

    jira {
        projectKey = "PROJ_D"
        majorVersionFormat = '$major.$minor'
    }

    "(,1.5)" {
        jira {
            releaseVersionFormat = '$major.$minor.$service'
        }
    }

    "[1.5,)" {
        jira {
            releaseVersionFormat = '$major.$minor.$service-$fix'
        }
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/xml-parser.git"
    }

    distribution {
        explicit = false
        external = true
    }
}

"message-broker" {
    componentDisplayName = "Message Broker"
    componentOwner = "owner-e"
    groupId = "org.octopusden.octopus.messaging"
    artifactId = "message-broker"

    jira {
        projectKey = "PROJ_E"
        technical = true
        displayName = "Message Broker Tech"
        component {
            versionPrefix = "broker"
        }
    }

    build {
        javaVersion = "11"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/message-broker.git"
    }

    distribution {
        explicit = false
        external = false
    }
}

"schema-validator" {
    componentDisplayName = "Schema Validator"
    componentOwner = "owner-d"
    groupId = "org.octopusden.octopus.schema"
    artifactId = "schema-validator"

    jira {
        projectKey = "PROJ_D"
    }

    build {
        buildTasks = "clean install"
        requiredProject = false
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/schema-validator.git"
    }

    distribution {
        explicit = false
        external = true
    }
}

"data-mapper" {
    componentDisplayName = "Data Mapper"
    componentOwner = "owner-d"
    groupId = "org.octopusden.octopus.datamapper,org.octopusden.octopus.datamapper.plugins"
    artifactId = "data-mapper-core,data-mapper-plugin"

    jira {
        projectKey = "PROJ_D"
        technical = true
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service.$fix-$build'
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/data-mapper.git"
    }

    distribution {
        explicit = false
        external = true
    }
}

// =============================================================================
// Group 3: IN_CONTAINER build system
// =============================================================================

"helm-chart-utils" {
    componentDisplayName = "Helm Chart Library Utils"
    componentOwner = "owner-e"
    groupId = "org.octopusden.octopus.helm"
    artifactId = "helm-chart-utils"
    buildSystem = IN_CONTAINER

    jira {
        projectKey = "PROJ_E"
        component {
            versionPrefix = "helm-utils"
        }
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/chart-library.git"
    }

    distribution {
        explicit = false
        external = false
        GAV = "org.octopusden.octopus.helm:helm-chart-utils:tgz"
    }
}

"helm-infra-chart" {
    componentDisplayName = "Helm Infrastructure Chart"
    componentOwner = "owner-e"
    groupId = "org.octopusden.octopus.helm"
    artifactId = "helm-infra-chart"
    buildSystem = IN_CONTAINER

    jira {
        projectKey = "PROJ_E"
        component {
            versionPrefix = "helm-infra"
        }
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/infra-chart.git"
    }

    distribution {
        explicit = false
        external = true
        docker = "registry/helm-infra"
    }
}

"docker-base-image" {
    componentDisplayName = "Docker Base Image"
    componentOwner = "owner-e"
    groupId = "org.octopusden.octopus.docker"
    artifactId = "base-image"
    buildSystem = IN_CONTAINER

    jira {
        projectKey = "PROJ_E"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/docker-base.git"
    }

    distribution {
        explicit = false
        external = true
    }
}

"container-toolkit" {
    componentDisplayName = "Container Toolkit"
    componentOwner = "owner-e"
    groupId = "org.octopusden.octopus.container"
    artifactId = "container-toolkit"
    buildSystem = IN_CONTAINER

    jira {
        projectKey = "PROJ_E"
    }

    "(,1.0)" {
        build {
            javaVersion = "1.8"
        }
    }

    "[1.0,)" {
        build {
            javaVersion = "17"
        }
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/container-toolkit.git"
    }

    distribution {
        explicit = false
        external = false
    }
}

// =============================================================================
// Group 4: ESCROW_PROVIDED_MANUALLY — customer deliverables
// =============================================================================

"customer-alpha-api" {
    componentDisplayName = "Customer Alpha API Integration"
    clientCode = "ALPHA"
    componentOwner = "owner-f"
    groupId = "org.octopusden.octopus.customers.ALPHA"
    artifactId = "customer-alpha-api"

    jira {
        projectKey = "PROJ_F"
        customer {
            versionPrefix = "alpha"
        }
    }

    buildSystem = ESCROW_PROVIDED_MANUALLY
    escrow {
        generation = EscrowGenerationMode.MANUAL
    }

    distribution {
        explicit = true
        external = true
        GAV = "org.octopusden.octopus.customers.ALPHA:customer-alpha-api:zip"
        securityGroups {
            read = "team-f#customer-rd"
        }
    }
}

"customer-beta-banking" {
    componentDisplayName = "Customer Beta Banking"
    clientCode = "BETA"
    componentOwner = "owner-f"
    groupId = "org.octopusden.octopus.customers.BETA"
    artifactId = "customer-beta-banking"

    jira {
        projectKey = "PROJ_F"
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service-$fix'
        displayName = "Beta Banking"
        customer {
            versionPrefix = "beta"
        }
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/customers-beta-banking.git"
        tag = 'beta-banking-$version'
    }

    buildSystem = ESCROW_PROVIDED_MANUALLY
    escrow {
        generation = EscrowGenerationMode.MANUAL
    }

    distribution {
        explicit = true
        external = true
        securityGroups {
            read = "team-f#banking-rd"
        }
    }
}

"customer-gamma-wallet" {
    componentDisplayName = "Customer Gamma Wallet"
    clientCode = "GAMMA"
    componentOwner = "owner-f"
    groupId = "org.octopusden.octopus.customers.GAMMA"
    artifactId = "customer-gamma-wallet"

    jira {
        projectKey = "PROJ_G"
        customer {
            versionPrefix = "gamma"
        }
    }

    "(,3.0)" {
        jira {
            releaseVersionFormat = '$major.$minor.$service'
        }
        vcsSettings {
            externalRegistry = "NOT_AVAILABLE"
        }
    }

    "(3.0,)" {
        jira {
            releaseVersionFormat = '$major.$minor.$service.$fix'
        }
        vcsSettings {
            externalRegistry = "NOT_AVAILABLE"
        }
    }

    buildSystem = ESCROW_PROVIDED_MANUALLY
    escrow {
        generation = EscrowGenerationMode.MANUAL
    }

    distribution {
        explicit = true
        external = true
        GAV = "org.octopusden.octopus.customers.GAMMA:customer-gamma-wallet:wim"
        securityGroups {
            read = "team-f#wallet-rd"
        }
    }
}

"partner-integration-east" {
    componentDisplayName = "Partner Integration East"
    clientCode = "PARTNER_E"
    componentOwner = "owner-f"
    groupId = "org.octopusden.octopus.partners.east"
    artifactId = "partner-integration-east"

    jira {
        projectKey = "PROJ_G"
        customer {
            versionPrefix = "east"
        }
    }

    vcsSettings {
        externalRegistry = "NOT_AVAILABLE"
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

"vendor-module-north" {
    componentDisplayName = "Vendor Module North"
    clientCode = "VENDOR_N"
    componentOwner = "owner-f"
    groupId = "org.octopusden.octopus.vendor.north"
    artifactId = "vendor-module-north"

    jira {
        projectKey = "PROJ_F"
        customer {
            versionPrefix = "north"
        }
    }

    buildSystem = ESCROW_PROVIDED_MANUALLY
    escrow {
        generation = EscrowGenerationMode.MANUAL
    }

    distribution {
        explicit = true
        external = true
        GAV = "org.octopusden.octopus.vendor.north:vendor-module-north:tgz"
    }
}

"vendor-module-south" {
    componentDisplayName = "Vendor Module South"
    clientCode = "VENDOR_S"
    componentOwner = "owner-f"
    groupId = "org.octopusden.octopus.vendor.south"
    artifactId = "vendor-module-south"

    jira {
        projectKey = "PROJ_F"
        customer {
            versionPrefix = "south"
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

// =============================================================================
// Group 5: ESCROW_NOT_SUPPORTED — aggregators and virtual components
// =============================================================================

"aggregator-core" {
    componentDisplayName = "Core Aggregator"
    componentOwner = "owner-d"
    groupId = "org.octopusden.octopus.aggregator"
    artifactId = "aggregator-core-stub"

    jira {
        component { versionPrefix = "aggregator-core" }
        projectKey = "PROJ_D"
    }

    "[99999,100000]" {
        // fake range to avoid intersection
    }

    buildSystem = ESCROW_NOT_SUPPORTED
    escrow {
        generation = EscrowGenerationMode.UNSUPPORTED
    }

    components {
        "core-util" {
            componentDisplayName = "Core Utility"
            componentOwner = "owner-d"
            artifactId = "core-util"
            buildSystem = MAVEN
            escrow {
                generation = EscrowGenerationMode.AUTO
            }

            "[1.0,)" {
                jira {
                    majorVersionFormat = '$major'
                    releaseVersionFormat = '$major.$minor.$service'
                    component {
                        versionPrefix = "core-util"
                    }
                }
            }

            vcsSettings {
                vcsUrl = "ssh://git@git.example.com/team/core-util.git"
            }
        }

        "core-lib" {
            componentDisplayName = "Core Library"
            componentOwner = "owner-d"
            artifactId = "core-lib"
            buildSystem = MAVEN
            escrow {
                generation = EscrowGenerationMode.AUTO
            }

            "(1.0,)" {
                jira {
                    majorVersionFormat = '$major'
                    releaseVersionFormat = '$major.$minor.$service'
                    component {
                        versionPrefix = "core-lib"
                    }
                }
            }

            vcsSettings {
                vcsUrl = "ssh://git@git.example.com/team/core-lib.git"
            }
        }
    }

    distribution {
        external = true
        explicit = false
    }
}

"tools-aggregator" {
    componentDisplayName = "Tools Aggregator"
    componentOwner = "owner-e"
    groupId = "org.octopusden.octopus.tools.aggr"
    artifactId = "tools-aggregator-stub"

    jira {
        projectKey = "PROJ_E"
    }

    "[99999,100000]" {}

    buildSystem = ESCROW_NOT_SUPPORTED
    escrow {
        generation = EscrowGenerationMode.UNSUPPORTED
    }

    components {
        "tool-runner" {
            componentDisplayName = "Tool Runner"
            componentOwner = "owner-e"
            artifactId = "tool-runner"
            buildSystem = MAVEN
            escrow {
                generation = EscrowGenerationMode.AUTO
            }

            jira {
                component {
                    versionPrefix = "tool-runner"
                }
            }

            vcsSettings {
                vcsUrl = "ssh://git@git.example.com/team/tool-runner.git"
            }
        }
    }

    distribution {
        explicit = false
        external = true
    }
}

"legacy-wrapper" {
    componentDisplayName = "Legacy System Wrapper"
    componentOwner = "owner-d"
    groupId = "org.octopusden.octopus.legacy"
    artifactId = "legacy-wrapper"

    jira {
        projectKey = "PROJ_D"
    }

    buildSystem = ESCROW_NOT_SUPPORTED
    escrow {
        generation = EscrowGenerationMode.UNSUPPORTED
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/legacy-wrapper.git"
    }

    distribution {
        explicit = false
        external = true
    }
}

// =============================================================================
// Group 6: Nested components (regular build systems)
// =============================================================================

"middleware-platform" {
    componentDisplayName = "Middleware Platform"
    componentOwner = "owner-g"
    groupId = "org.octopusden.octopus.middleware"
    artifactId = "middleware-platform"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_H"
        majorVersionFormat = '$major'
        releaseVersionFormat = '$major.$minor.$service'
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/middleware-platform.git"
        tag = 'middleware-$version'
    }

    build {
        javaVersion = "17"
    }

    components {
        "middleware-auth" {
            componentDisplayName = "Middleware Auth Module"
            componentOwner = "owner-g"
            artifactId = "middleware-auth"
            jira {
                component {
                    versionPrefix = "mw-auth"
                    versionFormat = '$versionPrefix.$baseVersionFormat'
                }
            }
            vcsSettings {
                vcsUrl = "ssh://git@git.example.com/team/middleware-auth.git"
                tag = 'mw-auth-$version'
            }
            distribution {
                explicit = false
                external = true
                docker = "registry/middleware-auth"
            }
        }

        "middleware-queue" {
            componentDisplayName = "Middleware Queue Module"
            componentOwner = "owner-g"
            artifactId = "middleware-queue"
            jira {
                component {
                    versionPrefix = "mw-queue"
                    versionFormat = '$versionPrefix.$baseVersionFormat'
                }
            }
            vcsSettings {
                vcsUrl = "ssh://git@git.example.com/team/middleware-queue.git"
            }
            distribution {
                explicit = false
                external = true
            }
        }
    }

    distribution {
        explicit = false
        external = true
    }
}

"web-banking-core" {
    componentDisplayName = "Web Banking Core"
    componentOwner = "owner-g"
    groupId = "org.octopusden.octopus.webbanking"
    artifactId = "web-banking-core"

    jira {
        projectKey = "PROJ_H"
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service-$fix'
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/web-banking-core.git"
    }

    components {
        "web-banking-ui" {
            componentDisplayName = "Web Banking UI"
            componentOwner = "owner-g"
            groupId = "org.octopusden.octopus.webbanking.ui"
            artifactId = "web-banking-ui"
            buildSystem = GRADLE
            jira {
                customer {
                    versionPrefix = "ui"
                }
            }
            vcsSettings {
                vcsUrl = "ssh://git@git.example.com/team/web-banking-ui.git"
                tag = 'wb-ui-$version'
            }
            distribution {
                explicit = true
                external = true
            }
        }

        "web-banking-api" {
            componentDisplayName = "Web Banking API"
            componentOwner = "owner-g"
            groupId = "org.octopusden.octopus.webbanking.api"
            artifactId = "web-banking-api"
            jira {
                customer {
                    versionPrefix = "api"
                }
            }
            vcsSettings {
                vcsUrl = "ssh://git@git.example.com/team/web-banking-api.git"
            }
            distribution {
                explicit = false
                external = true
            }
        }
    }

    distribution {
        explicit = false
        external = true
    }
}

"card-processing" {
    componentDisplayName = "Card Processing System"
    componentOwner = "owner-h"
    groupId = "org.octopusden.octopus.cardproc"
    artifactId = "card-processing"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_I"
        releaseVersionFormat = '$major.$minor.$service-$fix'
        component {
            versionPrefix = "cardproc"
        }
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/card-processing.git"
    }

    build {
        javaVersion = "17"
    }

    components {
        "card-processing-client" {
            componentDisplayName = "Card Processing Client SDK"
            componentOwner = "owner-h"
            groupId = "org.octopusden.octopus.cardproc.clients"
            artifactId = "card-processing-client"
            jira {
                customer {
                    versionPrefix = "client"
                }
            }
            vcsSettings {
                vcsUrl = "ssh://git@git.example.com/team/card-processing-client.git"
                tag = 'client-$version'
            }
            distribution {
                explicit = true
                external = true
            }
        }
    }

    distribution {
        explicit = false
        external = true
        docker = "registry/card-processing"
    }
}

// =============================================================================
// Group 7: Version-specific overrides
// =============================================================================

"transaction-engine" {
    componentDisplayName = "Transaction Engine"
    componentOwner = "owner-h"
    groupId = "org.octopusden.octopus.txn"
    artifactId = "transaction-engine"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_I"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/transaction-engine.git"
        tag = 'txn-$version'
    }

    "(,2.0)" {
        jira {
            majorVersionFormat = '$major.$minor'
            releaseVersionFormat = '$major.$minor.$service'
        }
    }

    "[2.0,3.0)" {
        jira {
            majorVersionFormat = '$major'
            releaseVersionFormat = '$major.$minor.$service'
        }
        build {
            javaVersion = "11"
        }
    }

    "[3.0,)" {
        jira {
            majorVersionFormat = '$major'
            releaseVersionFormat = '$major.$minor.$service-$fix'
        }
        build {
            javaVersion = "17"
        }
    }

    distribution {
        explicit = false
        external = true
    }
}

"settlement-service" {
    componentDisplayName = "Settlement Service"
    componentOwner = "owner-h"
    groupId = "org.octopusden.octopus.settlement"
    artifactId = "settlement-service"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_I"
        component {
            versionPrefix = "settlement"
            versionFormat = '$versionPrefix.$baseVersionFormat'
        }
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/settlement-service.git"
    }

    "(,1.5]" {
        vcsSettings {
            branch = "v1"
        }
    }

    "(1.5,)" {
        vcsSettings {
            branch = "main"
        }
    }

    distribution {
        explicit = false
        external = true
        docker = "registry/settlement"
    }
}

"fraud-detection" {
    componentDisplayName = "Fraud Detection Module"
    componentOwner = "owner-h"
    groupId = "org.octopusden.octopus.fraud"
    artifactId = "fraud-detection"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_I"
    }

    "(,2.0)" {
        build {
            javaVersion = "1.8"
            buildTasks = "assemble"
        }
    }

    "[2.0,)" {
        build {
            javaVersion = "17"
            buildTasks = "build"
        }
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/fraud-detection.git"
    }

    distribution {
        explicit = false
        external = true
    }
}

"compliance-module" {
    componentDisplayName = "Compliance Module"
    componentOwner = "owner-h"
    groupId = "org.octopusden.octopus.compliance"
    artifactId = "compliance-module"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_I"
        component {
            versionPrefix = "compliance"
            versionFormat = '$versionPrefix.$baseVersionFormat'
        }
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/compliance-module.git"
    }

    "[1.0,1.5)" {
        deprecated = true
    }

    "[1.5,)" {}

    distribution {
        explicit = false
        external = true
    }
}

"risk-assessment" {
    componentDisplayName = "Risk Assessment Service"
    componentOwner = "owner-h"
    groupId = "org.octopusden.octopus.risk"
    artifactId = "risk-assessment"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_I"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/risk-assessment.git"
    }

    "(,2.0]" {
        distribution {
            docker = "registry/risk-v1"
        }
    }

    "(2.0,)" {
        distribution {
            docker = "registry/risk-v2"
        }
    }

    distribution {
        explicit = false
        external = true
    }
}

"billing-system" {
    componentDisplayName = "Billing System"
    componentOwner = "owner-h"
    groupId = "org.octopusden.octopus.billing"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_I"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/billing-system.git"
    }

    "(,3.0)" {
        artifactId = "billing-core,billing-api"
    }

    "[3.0,)" {
        artifactId = "billing-core,billing-api,billing-reports"
    }

    distribution {
        explicit = false
        external = true
    }
}

"payment-switch" {
    componentDisplayName = "Payment Switch"
    componentOwner = "owner-h"
    groupId = "org.octopusden.octopus.payswitch"
    artifactId = "payment-switch"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_I"
    }

    "(,2.0)" {
        groupId = "org.octopusden.octopus.payswitch.legacy"
    }

    "[2.0,)" {}

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/payment-switch.git"
    }

    distribution {
        explicit = false
        external = true
    }
}

"terminal-driver" {
    componentDisplayName = "Terminal Driver"
    componentOwner = "owner-d"
    groupId = "org.octopusden.octopus.terminal"
    artifactId = "terminal-driver"

    jira {
        projectKey = "PROJ_D"
        component { versionPrefix = "terminal" }
    }

    "(,2.0)" {
        buildSystem = ESCROW_NOT_SUPPORTED
        escrow {
            generation = EscrowGenerationMode.UNSUPPORTED
        }
    }

    "[2.0,3.0)" {
        vcsSettings {
            vcsUrl = "ssh://git@git.example.com/team/terminal-driver.git"
        }
        buildSystem = MAVEN
        build { requiredProject = false }
        jira {
            majorVersionFormat = '$major.$minor'
            releaseVersionFormat = '$major.$minor.$service.$fix-$build'
        }
    }

    "[3.0,)" {
        vcsSettings {
            vcsUrl = "ssh://git@git.example.com/team/terminal-driver.git"
        }
        buildSystem = GRADLE
        jira {
            majorVersionFormat = '$major'
            releaseVersionFormat = '$major.$minor.$service'
        }
    }

    distribution {
        external = true
        explicit = false
    }
}

"reconciliation-engine" {
    componentDisplayName = "Reconciliation Engine"
    componentOwner = "owner-h"
    groupId = "org.octopusden.octopus.recon"
    artifactId = "reconciliation-engine"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_I"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/reconciliation.git"
        tag = 'recon-$version'
    }

    "(,1.0.50]" {
        vcsSettings {
            branch = "v1.0"
        }
    }

    "(1.0.50,)" {
        vcsSettings {
            branch = "main"
        }
    }

    distribution {
        explicit = false
        external = true
    }
}

"tokenization-service" {
    componentDisplayName = "Tokenization Service"
    componentOwner = "owner-h"
    groupId = "org.octopusden.octopus.token"
    artifactId = "tokenization-service"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_I"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/tokenization.git"
    }

    "(,2.0)" {
        distribution {
            explicit = true
            external = true
            GAV = "org.octopusden.octopus.token:tokenization-service:zip"
        }
    }

    "[2.0,)" {
        distribution {
            explicit = true
            external = true
            GAV = "org.octopusden.octopus.token:tokenization-service:tgz"
            docker = "registry/tokenization"
        }
    }
}

// =============================================================================
// Group 8: Docker distribution variants
// =============================================================================

"stream-processor" {
    componentDisplayName = "Stream Processor"
    componentOwner = "owner-b"
    groupId = "org.octopusden.octopus.stream"
    artifactId = "stream-processor"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_B"
    }

    build {
        javaVersion = "17"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/stream-processor.git"
        tag = 'stream-$version'
    }

    distribution {
        explicit = false
        external = true
        docker = "registry/stream-processor"
    }
}

"api-mesh" {
    componentDisplayName = "API Mesh Service"
    componentOwner = "owner-b"
    groupId = "org.octopusden.octopus.mesh"
    artifactId = "mesh-core,mesh-proxy,mesh-discovery"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_B"
        component {
            versionPrefix = "ApiMesh"
            versionFormat = '$versionPrefix.$baseVersionFormat'
        }
    }

    build {
        javaVersion = "17"
        requiredProject = false
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/api-mesh.git"
        tag = 'mesh-$version'
    }

    distribution {
        explicit = false
        external = true
        docker = "registry/mesh-core,registry/mesh-proxy,registry/mesh-discovery"
    }
}

"cloud-gateway" {
    componentDisplayName = "Cloud Gateway"
    componentOwner = "owner-b"
    groupId = "org.octopusden.octopus.cloudgw"
    artifactId = "cloud-gateway"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_B"
    }

    build {
        javaVersion = "17"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/cloud-gateway.git"
    }

    distribution {
        explicit = false
        external = true
        docker = "registry/cloud-gateway"
    }
}

"service-mesh" {
    componentDisplayName = "Service Mesh"
    componentOwner = "owner-b"
    groupId = "org.octopusden.octopus.svcmesh"
    artifactId = "svcmesh-agent,svcmesh-control,svcmesh-data"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_B"
        component {
            versionPrefix = "SvcMesh"
            versionFormat = '$versionPrefix.$baseVersionFormat'
        }
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/service-mesh.git"
    }

    distribution {
        explicit = false
        external = true
        docker = "registry/svcmesh-agent,registry/svcmesh-control,registry/svcmesh-data"
    }
}

"container-runtime" {
    componentDisplayName = "Container Runtime"
    componentOwner = "owner-e"
    groupId = "org.octopusden.octopus.crt"
    artifactId = "container-runtime"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_E"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/container-runtime.git"
    }

    distribution {
        explicit = false
        external = true
        docker = "registry/container-runtime:jdk17"
    }
}

"observability-agent" {
    componentDisplayName = "Observability Agent"
    componentOwner = "owner-b"
    groupId = "org.octopusden.octopus.observability"
    artifactId = "observability-agent"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_B"
    }

    build {
        javaVersion = "17"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/observability-agent.git"
    }

    distribution {
        explicit = false
        external = true
        docker = "registry/observability-agent"
    }
}

// =============================================================================
// Group 9: Explicit GAV distribution
// =============================================================================

"desktop-client" {
    componentDisplayName = "Desktop Client Application"
    componentOwner = "owner-g"
    groupId = "org.octopusden.octopus.desktop"
    artifactId = "desktop-client"

    jira {
        projectKey = "PROJ_H"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/desktop-client.git"
    }

    distribution {
        explicit = true
        external = true
        GAV = "org.octopusden.octopus.desktop:desktop-client:war"
        securityGroups {
            read = "team-g#desktop-rd"
        }
    }
}

"admin-console" {
    componentDisplayName = "Administration Console"
    componentOwner = "owner-g"
    groupId = "org.octopusden.octopus.admin"
    artifactId = "admin-console,admin-api"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_H"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/admin-console.git"
    }

    distribution {
        explicit = true
        external = true
        GAV = "org.octopusden.octopus.admin:admin-console:zip"
    }
}

"sdk-distribution" {
    componentDisplayName = "SDK Distribution Package"
    componentOwner = "owner-g"
    groupId = "org.octopusden.octopus.sdk"
    artifactId = "sdk-core,sdk-tools"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_H"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/sdk-distribution.git"
    }

    distribution {
        explicit = true
        external = true
        GAV = "org.octopusden.octopus.sdk:sdk-distribution:tgz"
    }
}

"firmware-update" {
    componentDisplayName = "Firmware Update Package"
    componentOwner = "owner-d"
    groupId = "org.octopusden.octopus.firmware"
    artifactId = "firmware-update"

    jira {
        projectKey = "PROJ_D"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/firmware-update.git"
    }

    distribution {
        GAV = "org.octopusden.octopus.firmware:firmware-update:zip," +
              "org.octopusden.octopus.firmware:firmware-tools:zip" +
              ',file:///firmware:$major-$minor-$service'
        explicit = true
        external = true
        securityGroups {
            read = "team-d#firmware-rd"
        }
    }
}

// =============================================================================
// Group 10: clientCode — regional modules
// =============================================================================

"regional-module-na" {
    componentDisplayName = "Regional Module North America"
    clientCode = "REGION_NA"
    componentOwner = "owner-f"
    groupId = "org.octopusden.octopus.regional.na"
    artifactId = "regional-module-na"

    jira {
        projectKey = "PROJ_G"
        customer {
            versionPrefix = "na"
        }
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/regional-na.git"
        tag = 'regional-na-$version'
    }

    distribution {
        explicit = true
        external = true
        securityGroups {
            read = "team-f#regional-rd"
        }
    }
}

"regional-module-eu" {
    componentDisplayName = "Regional Module Europe"
    clientCode = "REGION_EU"
    componentOwner = "owner-f"
    groupId = "org.octopusden.octopus.regional.eu"
    artifactId = "regional-module-eu"

    jira {
        projectKey = "PROJ_G"
        customer {
            versionPrefix = "eu"
        }
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/regional-eu.git"
    }

    "[1.0,2.0)" {}

    distribution {
        explicit = true
        external = true
    }
}

"regional-module-asia" {
    componentDisplayName = "Regional Module Asia"
    clientCode = "REGION_ASIA"
    componentOwner = "owner-f"
    groupId = "org.octopusden.octopus.regional.asia"
    artifactId = "regional-module-asia"

    jira {
        projectKey = "PROJ_G"
        customer {
            versionPrefix = "asia"
        }
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/regional-asia.git"
    }

    distribution {
        explicit = true
        external = true
    }
}

// =============================================================================
// Group 11: Technical jira components
// =============================================================================

"devops-toolchain" {
    componentDisplayName = "DevOps Toolchain"
    componentOwner = "owner-e"
    groupId = "org.octopusden.octopus.devops"
    artifactId = "devops-toolchain"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_E"
        technical = true
        displayName = "DevOps Toolchain"
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/devops-toolchain.git"
    }

    distribution {
        explicit = false
        external = false
    }
}

"build-plugin" {
    componentDisplayName = "Build Plugin"
    componentOwner = "owner-e"
    groupId = "org.octopusden.octopus.buildplugin"
    artifactId = "build-plugin"

    jira {
        projectKey = "PROJ_E"
        technical = true
        displayName = "Build Plugin"
        component {
            versionPrefix = "build-plugin"
        }
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/build-plugin.git"
    }

    distribution {
        explicit = false
        external = false
    }
}

"ci-pipeline" {
    componentDisplayName = "CI Pipeline"
    componentOwner = "owner-e"
    groupId = "org.octopusden.octopus.cipipe"
    artifactId = "ci-pipeline"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_E"
        technical = true
        displayName = "CI Pipeline"
        component {
            versionPrefix = "ci"
            versionFormat = '$versionPrefix.$baseVersionFormat'
        }
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/ci-pipeline.git"
    }

    distribution {
        explicit = false
        external = false
    }
}

// =============================================================================
// Group 12: No explicit jira — inherits from Defaults
// =============================================================================

"utility-common" {
    componentOwner = "owner-d"
    groupId = "org.octopusden.octopus.util.common"
    artifactId = "utility-common"

    jira {
        projectKey = "PROJ_D"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/utility-common.git"
    }

    distribution {
        explicit = false
        external = true
    }
}

"string-utils" {
    componentOwner = "owner-d"
    groupId = "org.octopusden.octopus.util.string"
    artifactId = "string-utils"

    jira {
        projectKey = "PROJ_D"
    }

    distribution {
        explicit = false
        external = false
    }
}

"date-utils" {
    componentOwner = "owner-d"
    groupId = "org.octopusden.octopus.util.date"
    artifactId = "date-utils"

    jira {
        projectKey = "PROJ_D"
    }

    distribution {
        explicit = false
        external = false
    }
}

"io-helper" {
    componentOwner = "owner-d"
    groupId = "org.octopusden.octopus.util.io"
    artifactId = "io-helper"

    jira {
        projectKey = "PROJ_D"
    }

    distribution {
        explicit = false
        external = true
    }
}

"network-utils" {
    componentOwner = "owner-d"
    groupId = "org.octopusden.octopus.util.net"
    artifactId = "network-utils"

    jira {
        projectKey = "PROJ_D"
    }

    distribution {
        explicit = false
        external = true
    }
}

// =============================================================================
// Group 13: Miscellaneous special patterns
// =============================================================================

"solution-banking" {
    componentDisplayName = "Banking Solution"
    componentOwner = "owner-h"
    clientCode = "SOLUTION_BANK"
    solution = true
    groupId = "org.octopusden.octopus.solution.banking"
    artifactId = "solution-banking"
    buildSystem = GRADLE

    escrow {
        generation = EscrowGenerationMode.AUTO
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/banking-solution.git"
        branch = 'main|v$major.$minor'
    }

    jira {
        projectKey = "PROJ_I"
        component {
            versionPrefix = "solution-banking"
            versionFormat = '$versionPrefix.$baseVersionFormat'
        }
    }

    distribution {
        explicit = true
        external = true
        GAV = "org.octopusden.octopus.solution.banking:distribution:tgz"
    }
}

"document-manager" {
    componentDisplayName = "Document Manager"
    componentOwner = "owner-g"
    releaseManager = "manager-b"
    securityChampion = "champion-b"
    copyright = "Example Corp"
    releasesInDefaultBranch = false
    labels = ['Label1']
    groupId = "org.octopusden.octopus.docmgr"
    artifactId = "document-manager"
    system = "CLASSIC,ALFA"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_H"
        majorVersionFormat = '$major'
        releaseVersionFormat = '$major.$minor'
        buildVersionFormat = '$major.$minor.$service'
        hotfixVersionFormat = '$major.$minor.$service.$fix'
        displayName = "Document Manager Display"
    }

    build {
        requiredTools = "BuildEnv"
        javaVersion = "11"
        mavenVersion = "3.6.3"
        gradleVersion = "LATEST"
        buildTasks = "clean build"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/document-manager.git"
        branch = "v2"
        hotfixBranch = 'hotfix/$major.$minor.$service'
    }

    distribution {
        explicit = false
        external = false
        GAV = "org.octopusden.octopus.docmgr:document-manager:jar"
        docker = "registry/document-manager"
    }

    buildFilePath = "build"
}

"vcs-share-tool" {
    componentOwner = "owner-d"
    "$ALL_VERSIONS" {
        vcsUrl = "ssh://git@git.example.com/team/vcs-share.git"
        artifactId = "dummy"
        groupId = "org.octopusden.octopus.vcsshare"
    }
    jira {
        projectKey = "PROJ_D"
        component {
            versionPrefix = "vcs-share"
        }
    }
    distribution {
        explicit = false
        external = false
    }
}

"multi-root-service" {
    componentOwner = "owner-d"
    groupId = "org.octopusden.octopus.multiroot"
    artifactId = "multi-root-service"
    vcsSettings {
        primary {
            repositoryType = GIT
            vcsUrl = "ssh://git@git.example.com/team/multi-root-primary.git"
            branch = "main"
        }

        secondary {
            repositoryType = GIT
            vcsUrl = "ssh://git@git.example.com/team/multi-root-secondary.git"
            branch = "develop"
        }
    }
    jira {
        projectKey = "PROJ_D"
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
        customer {
            versionPrefix = "multiroot"
        }
    }
}

"external-registry-ref" {
    componentDisplayName = "External Registry Reference"
    componentOwner = "owner-d"
    groupId = "org.octopusden.octopus.extref"
    artifactId = "external-registry-ref"

    jira {
        projectKey = "PROJ_D"
        customer {
            versionPrefix = "extref"
            versionFormat = '$versionPrefix.$baseVersionFormat'
        }
    }

    distribution {
        explicit = true
        external = true
    }

    vcsSettings {
        externalRegistry = "db-kernel"
    }
}

"branch-template-svc" {
    componentDisplayName = "Branch Template Service"
    componentOwner = "owner-c"
    groupId = "org.octopusden.octopus.branchtpl"
    artifactId = "branch-template-svc"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_C"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/branch-template.git"
        branch = 'master|$major.$minor'
        hotfixBranch = 'hotfix/$major.$minor.$service'
    }

    build {
        javaVersion = "17"
    }

    distribution {
        explicit = false
        external = true
    }
}

"custom-tag-service" {
    componentDisplayName = "Custom Tag Service"
    componentOwner = "owner-c"
    groupId = "org.octopusden.octopus.customtag"
    artifactId = "custom-tag-service"
    tag = 'release/$version'
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_C"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/custom-tag-service.git"
    }

    distribution {
        explicit = false
        external = true
    }
}

"scheduler-service" {
    componentDisplayName = "Task Scheduler Service"
    componentOwner = "owner-b"
    groupId = "org.octopusden.octopus.scheduler"
    artifactId = "scheduler-core,scheduler-worker"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_B"
        component {
            versionPrefix = "scheduler"
            versionFormat = '$versionPrefix.$baseVersionFormat'
        }
    }

    build {
        javaVersion = "17"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/scheduler-service.git"
        tag = 'scheduler-$version'
    }

    distribution {
        explicit = false
        external = true
        docker = "registry/scheduler-core,registry/scheduler-worker"
    }
}

"file-storage" {
    componentDisplayName = "File Storage Service"
    componentOwner = "owner-b"
    groupId = "org.octopusden.octopus.filestorage"
    artifactId = "file-storage-api,file-storage-service"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_B"
        majorVersionFormat = '$major'
        releaseVersionFormat = '$major.$minor.$service'
    }

    build {
        javaVersion = "17"
        requiredProject = false
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/file-storage.git"
        tag = 'file-storage-$version'
    }

    distribution {
        explicit = false
        external = true
    }
}

"event-bus" {
    componentDisplayName = "Event Bus"
    componentOwner = "owner-b"
    groupId = "org.octopusden.octopus.eventbus"
    artifactId = "event-bus"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_B"
    }

    build {
        javaVersion = "11"
        systemProperties = "-Pprofile=production"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/event-bus.git"
    }

    distribution {
        explicit = false
        external = true
    }
}

"data-sync" {
    componentDisplayName = "Data Synchronization Tool"
    componentOwner = "owner-d"
    groupId = "org.octopusden.octopus.datasync"
    artifactId = "data-sync"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_D"
    }

    buildFilePath = "data-sync-module"

    build {
        buildTasks = "build"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/data-sync.git"
    }

    distribution {
        explicit = false
        external = true
    }
}

"report-generator" {
    componentDisplayName = "Report Generator"
    componentOwner = "owner-g"
    groupId = "org.octopusden.octopus.reports"
    artifactId = "report-generator"
    buildSystem = GRADLE

    jira {
        projectKey = "PROJ_H"
    }

    vcsSettings {
        vcsUrl = "ssh://git@git.example.com/team/report-generator.git"
    }

    distribution {
        explicit = true
        external = true
        GAV = "org.octopusden.octopus.reports:report-generator:zip"
        securityGroups {
            read = "team-g#reports-rd"
        }
    }
}
