import org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode

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
    build {
        requiredProject = true
        projectVersion = "03.42"
        mavenVersion = "3.3.9"
    }

    distribution {
        explicit = true
        external = true
        GAV='file:///${env.CONF_PATH}/tscomponent/Core/${version}/ts-${version}.zip?artifactId=ts'
    }

    components {
        "sub-component-one" {
            componentOwner = "user1"
            groupId = "org.octopusden.octopus.sub-component-one"
            octopusVersion = '03.47'
            vcsUrl = "ssh://hg@mercurial/sub-component-one"
            jira {
                component { versionPrefix = "one" }
            }
            "[1.0,1.0.336)" {
                escrow {
                    generation = EscrowGenerationMode.MANUAL
                }
            }
        }

        "sub-component-two" {
            componentOwner = "user1"
            groupId = "org.octopusden.octopus.sub-component-two"
            vcsUrl = "ssh://hg@mercurial/sub-component-two"
            jira {
                component { versionPrefix = "two" }
            }
        }
    }
}
