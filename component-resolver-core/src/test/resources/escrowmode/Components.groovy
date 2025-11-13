import org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode

import static org.octopusden.octopus.escrow.BuildSystem.MAVEN

"TEST_COMPONENT3" {
    componentDisplayName = "3-D Secure"
    componentOwner = "user4"
    releaseManager = "user4"
    securityChampion = "user4"
    vcsUrl = 'ssh://hg@mercurial/tdsecure'
    groupId = "org.octopusden.octopus.test"
    tag = 'octopustds-$version'
//    branch = "default"
    jira {
        projectKey = "TDS"
    }
    build {
        javaVersion = "1.8"
    }
    escrow {
        generation = EscrowGenerationMode.AUTO
    }
    distribution {
        GAV = "org.octopusden.octopus.test:octopusmpi:war," +
                "org.octopusden.octopus.test:octopusacs:war," +
                'org.octopusden.octopus.test:demo:war,file:///${env.CONF_PATH}/NDC_DDC_Configuration_Builder/${major}.${minor}/NDC-DDC-Configuration-Builder-${major}.${minor}.${service}.exe'
        explicit = true
        external = true
    }

    "(,1.0.107)" {
    }

    "[1.0.107,)" {
        escrow {
            generation = EscrowGenerationMode.MANUAL
        }
        jira {
            releaseVersionFormat = '$major.$minor.$service-$fix'
        }
        tag = 'tdsecure-$version'
    }
}

app {
    componentOwner = "user1"
    vcsUrl = 'ssh://hg@mercurial//server/release'
    groupId = "org.octopusden.octopus.server"
    artifactId = "server"
    escrow {
        generation = EscrowGenerationMode.UNSUPPORTED
    }
    components {
        "jdk" {
            componentOwner = "user1"
            groupId = "org.octopusden.octopus.server.jdk"
            buildSystem = MAVEN
            vcsSettings {
                vcsUrl = "ssh://hg@mercurial//server/jdk"
                tag = '$module-$version'
            }
            jira {
                projectKey = "AS-JDK"
            }
        }

        "jdk-manual" {
            componentOwner = "user1"
            groupId = "org.octopusden.octopus.server.jdk-manual"
            buildSystem = MAVEN
            escrow {
                generation = EscrowGenerationMode.MANUAL
            }
            vcsSettings {
                vcsUrl = "ssh://hg@mercurial//server/jdk-manual"
                tag = '$module-$version'
            }
            jira {
                projectKey = "AS-JDK-MANUAL"
            }
        }
    }
    jira {
        projectKey = "AS"
    }
}
