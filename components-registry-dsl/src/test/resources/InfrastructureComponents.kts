import org.octopusden.octopus.components.registry.dsl.GIT
import org.octopusden.octopus.components.registry.dsl.GRADLE
import org.octopusden.octopus.components.registry.dsl.PT_K
import org.octopusden.octopus.components.registry.dsl.component

component("DDD") {
    productType = PT_K
    build {
       tools {
           database {
               oracle {
                  version = "[12,)"
               }
           }
       }
    }
}

component("TEST_COMPONENT2") {
    build {
        tools {
            product {
                 ptc {
                }
            }
        }
    }
}

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
        component("isolate_script")  {
            displayName = "RND Escrow Isolate Script"
            git {
                vcsUrl = "git@gitlab:/vSphere/escrow_isolate_build.git"
            }
            groupId = "org.octopusden.octopus.escrow.isolate"
            artifactId = listOf("isolate-script")
            build {
                buildSystem {
                    type = GRADLE
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
        artifactId = listOf("ci-its-api",
                "jira-its",
                "ci-teamcity",
                "client",
                "jira-relnotes-dto",
                "maven-crm-plugin",
                "releng-plugin",
                "releng-parent",
                "func-tests",
                "release-notes",
                "release-helper",
                "test-utilities",
                "releng-bom",
                "releng-client",
                "reports-generator",
                "test-helper-jira-plugin",
                "wiki-publisher")
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
                vcsUrl = "ssh://hg@mercurial//jira-fisheye-plugin"
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

        component("vcs-facade") {
            groupId = "org.octopusden.octopus.vcsfacade"
            artifactId = listOf("vcs-facade")
            build {
                buildSystem {
                    type = GRADLE
                }
            }
            vcsSettings {
                vcsUrl = "git@gitlab:/vcs-facade.git"
                repositoryType = GIT
                branch = "master"
            }

            jira {
                displayName = "vcs-facade"
                majorVersionFormat = "\$major.\$minor"
                releaseVersionFormat = "\$major.\$minor.\$service"
                component {
                    versionPrefix = "vcs-facade"
                    versionFormat = "\$versionPrefix.\$baseVersionFormat"
                }
            }
        }

        component("versions-api") {
            displayName = "versions-api"
            vcsSettings {
                vcsUrl = "ssh://hg@mercurial/versions-api"
                branch = "default"
                tag = "\$module-\$version"
            }
            groupId = "org.octopusden.octopus.releng"
            artifactId = listOf("versions-api")
            jira {
                majorVersionFormat = "\$major"
                releaseVersionFormat = "\$major.\$minor"
                technical = true
                component {
                    versionPrefix = "versions-api"
                    versionFormat = "\$versionPrefix.\$baseVersionFormat"
                }
            }
        }

        component("releng-lib") {
            vcsSettings {
                vcsUrl = "ssh://hg@mercurial/releng/releng-lib"
                branch = "default"
                tag = "\$module-\$version"
            }
            groupId = "org.octopusden.octopus.releng"
            artifactId = listOf("releng-lib")


            version("[1.39.1,1.40)")  {
                jira {
                    majorVersionFormat = "\$major.\$minor"
                    releaseVersionFormat = "\$major.\$minor.\$service"
                    buildVersionFormat = "\$major.\$minor.\$service"
                    displayName = "releng-lib"
                    technical = true
                    component {
                        versionPrefix = "releng-lib"
                        versionFormat = "\$versionPrefix.\$baseVersionFormat"
                    }
                }
            }

            version("(,1.39.1),[1.40,)") {
                jira {
                    releaseVersionFormat = "\$major.\$minor"
                    majorVersionFormat = "\$major"
                    displayName = "releng-lib"
                    technical = true
                    component {
                        versionPrefix = "releng-lib"
                        versionFormat = "\$versionPrefix.\$baseVersionFormat"
                    }
                }
            }
        }

        component("components-integration") {
            displayName = "components-integration"
            build {
                buildSystem {
                    gradle()
                }
            }
            vcsSettings {
                repositoryType = GIT
                vcsUrl = "git@gitlab:release-management/components-integration.git"
                tag = "components-integration-\$version"
            }
            groupId = "org.octopusden.octopus.releng.components.integration"
            artifactId = listOf("components-integration-core")
            jira {
                majorVersionFormat = "\$major"
                releaseVersionFormat = "\$major.\$minor"
                component {
                    versionPrefix = "components-integration"
                    versionFormat = "\$versionPrefix.\$baseVersionFormat"
                }
            }
        }

        component("release-management-gradle-plugin") {
            build {
                buildSystem {
                    gradle()
                }
            }
            vcsSettings {
                repositoryType = GIT
                vcsUrl = "git@gitlab:release-management/release-management-gradle-plugin.git"
                tag = "release-management-gradle-plugin-\$version"
            }
            groupId = "org.octopusden.octopus.release-management"
            artifactId = listOf("org.octopusden.octopus.release-management.gradle.plugin", "org.octopusden.octopus.release-management")
            jira {
                majorVersionFormat = "\$major.\$minor"
                releaseVersionFormat = "\$major.\$minor.\$service"
                displayName = "release-management-gradle-plugin"
                component {
                    versionPrefix = "release-management-gradle-plugin"
                    versionFormat = "\$versionPrefix.\$baseVersionFormat"
                }
            }
        }
    }
}

