import org.octopusden.octopus.components.registry.dsl.GIT
import org.octopusden.octopus.components.registry.dsl.component

component("escrow-generator") {
    vcsSettings {
        repositoryType = GIT
        vcsUrl = "ssh://git@github.com/octopusden/escrow-generator.git"
    }
    groupId = "org.octopusden.octopus.escrow"
    jira {
        projectKey = "ECOMPONENT"
        majorVersionFormat = "\$major"
    }

    version("(,4.84],[4.85,)")  {
        jira {
            releaseVersionFormat = "\$major.\$minor"
        }
    }

    version("(4.84,4.85)") {
        jira {
            releaseVersionFormat = "\$major.\$minor.\$service"
        }
        vcsSettings {
            branch = "releng-v1"
        }
    }

    build {
        javaVersion = "1.8"
    }

    components {
        component("isolate-script")  {
            displayName = "RND Escrow Isolate Script"
            git {
                vcsUrl = "git@gitlab:gr/vSphere/escrow_isolate_build.git"
            }
            groupId = "org.octopusden.octopus.escrow.isolate"
            artifactId = listOf("isolate-script")
            build {
                buildSystem {
                    gradle()
                }
            }
            jira {
                majorVersionFormat = "\$major.\$minor"
                releaseVersionFormat = "\$major.\$minor.\$service"
                component {
                    versionPrefix = "isolate-script"
                    versionFormat = "\$versionPrefix.\$baseVersionFormat"
                }
            }
        }
    }
}
