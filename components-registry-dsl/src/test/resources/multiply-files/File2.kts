import org.octopusden.octopus.components.registry.dsl.GIT
import org.octopusden.octopus.components.registry.dsl.component

component("releng") {
    jira {
        projectKey = "RELENG"
        majorVersionFormat = "\$major"
        releaseVersionFormat = "\$major.\$minor"
    }

    distribution {
        explicit = false
        external = true
    }


    build {
        javaVersion = "1.8"
    }

    vcsSettings {
        vcsUrl = "ssh://hg@mercurial/releng"
        tag = "releng-R-\$version"
    }

    version("[4.0,6)")  {
        vcsSettings {
            branch = "default"
        }
        groupId = "org.octopusden.octopus.releng,org.octopusden.octopus.ci-its"
        artifactId = listOf("ci-its-api", "wiki-publisher")
    }
    version("[6,7)")  {
        vcsSettings {
            branch = "v2"
        }
        groupId = "org.octopusden.octopus.releng"
        artifactId = listOf("releng-plugin")
    }
    version("[7,)") {
        groupId = "org.octopusden.octopus.releng"
        artifactId = listOf("releng-plugin")
        vcsSettings {
            vcsUrl = "git@gitlab:release-management/releng.git"
            repositoryType = GIT
            branch = "master"
        }
    }
    components {
        component ("jira-fisheye-plugin") {
            vcsSettings {
                vcsUrl = "ssh://hg@mercurial/jira-fisheye-plugin"
                branch = "default"
                tag = "\$module-\$version"
            }
            groupId = "org.octopusden.octopus.jirafisheyeplugin"
            artifactId = listOf("jira-fisheye-plugin")
            jira {
                majorVersionFormat = "\$major"
                releaseVersionFormat = "\$major.\$minor"
                displayName = "OW JIRA Fisheye Plugin"
                component {
                    versionPrefix = "fisheye"
                    versionFormat = "\$versionPrefix-\$baseVersionFormat"
                }
            }
        }
    }
}

