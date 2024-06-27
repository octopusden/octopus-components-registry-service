import static org.octopusden.octopus.escrow.BuildSystem.*
import static org.octopusden.octopus.escrow.RepositoryType.*


Defaults {
    system = "NONE"
    releasesInDefaultBranch = true
    solution = false
    jira {
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
    }
}


octopusweb {
    jira {
        projectKey = "WCOMPONENT"
        displayName = "WCOMPONENT"
    }


    "[2.1,)" {
        groupId = "org.octopusden.octopus.octopusweb"
        buildSystem = PROVIDED
        jira {
            majorVersionFormat = '$major.$minor.$service'
            releaseVersionFormat = '$major.$minor.$service-$fix'
            customer {
                versionPrefix = "WCOMPONENTBB"
                versionFormat = '$versionPrefix-$baseVersionFormat'
            }
        }
        distribution {
            explicit = true
            external = true
        }
    }

    "[,2.1)" {
        groupId = "org.octopusden.octopus.octopusweb"
        buildSystem = PROVIDED
        jira {
            majorVersionFormat = '$major.$minor'
            releaseVersionFormat = '$major.$minor.$service'
        }
    }

    components {
        "buildsystem-model" {
            groupId = "org.octopusden.octopus.buildsystem.model"
            artifactId = /[\w-\.]+/

            "[1.3,)" {
                vcsUrl = "ssh://hg@mercurial//buildsystem-model"
                jira {
                    majorVersionFormat = 'Model.$major.$minor.$service'
                    releaseVersionFormat = 'Model.$version'
                }
            }

            build {
                javaVersion = "1.6"
                requiredProject = false
                systemProperties = "-D1.6"
                projectVersion = "03.1.6"
            }

            branch = "1.6-branch"
            deprecated = true
        }

        "buildsystem-mojo" {
            repositoryType = MERCURIAL
            artifactId = "buildsystem-maven-plugin"
            vcsUrl = "ssh://hg@mercurial/maven-buildsystem-plugin"
            tag = 'maven-buildsystem-plugin-$version'
            jira {
                majorVersionFormat = 'Mojo.$major.$minor'
                releaseVersionFormat = 'Mojo.$major.$minor.$service'
            }
        }
    }
}

"mobile-web-banking" {
    jira {
        projectKey = "component-m"
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$version'
    }
    groupId = "org.octopusden.octopus.mobileweb"
    vcsUrl = "ssh://hg@mercurial/ic/component-m/main"
    tag = 'component-m/main-$version'
}


TEST_COMPONENT2 {
    componentOwner = "user1"
    jira {
        projectKey = "TEST_COMPONENT2"
        majorVersionFormat = '$major02.$minorC.$serviceC'
        releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
    }
    buildSystem = PROVIDED;
    groupId = "org.octopusden.octopus.componentc"
    distribution {
        explicit = true
        external = true
    }
}

