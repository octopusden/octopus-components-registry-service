import static org.octopusden.octopus.escrow.BuildSystem.GRADLE
import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.CVS
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL

final ANY_ARTIFACT = /[\w-\.]+/

Tools {
    BuildEnv {
        escrowEnvironmentVariable = "BUILD_ENV"
        targetLocation = "tools/BUILD_ENV"
        sourceLocation = "env.BUILD_ENV"
    }

    BuildLib {
        escrowEnvironmentVariable = "BUILD_LIB"
        targetLocation = "tools/BuildLib"
        sourceLocation = "env.BUILD_LIB"
    }
}

Defaults {
    repositoryType = CVS
    buildSystem = GRADLE
    tag = '$module-$version'
    artifactId = ANY_ARTIFACT
    jira {
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
//        customer {
//            versionFormat = '$versionPrefix-$baseVersionFormat'
//        }
    }
    build {
        requiredTools = "BuildEnv"
        javaVersion = "1.8"
        mavenVersion = "3.3.9"
        gradleVersion = "2.10"
    }
    distribution {
        explicit = false
        external = true
    }

}

bcomponent {
    buildSystem = MAVEN
    repositoryType = MERCURIAL
    vcsUrl = "ssh://hg@mercurial/bcomponent"
    groupId = "org.octopusden.octopus.bcomponent"
    tag = '$module-R-$version'
    artifactId = "bcomponent"
    deprecated = false
    branch = "1.8-branch"
    componentDisplayName = "Human readable BCOMPONENT name"
    componentOwner = "someowner"
    releaseManager = "somereleasemanager"
    securityChampion = "somesecuritychampion"
    system = "CLASSIC"

    distribution {
        explicit = true
        external = true
    }

    build {
        javaVersion = "1.8"
        mavenVersion = "1.8-maven"
        gradleVersion = "1.8-gradle"
        requiredProject = true
        systemProperties = "-D1.8"
        projectVersion = "03.1.8"
        requiredTools = "BuildEnv,BuildLib"
    }

    jira {
        projectKey = "BCOMPONENT"
        lineVersionFormat = '$major'
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service-$fix'
        buildVersionFormat = '$major.$minor.$service.$fix-$build'
        technical = false
    }


    components {
        "buildsystem-model" {
            groupId = "org.octopusden.octopus.buildsystem.model"
            artifactId = /[\w-\.]+/
            jira {
                lineVersionFormat = '$major'
                majorVersionFormat = '$major.$minor'
                releaseVersionFormat = '$major.$minor.$service'
                buildVersionFormat = '$major.$minor.$service.$build'
                technical = true
                component {
                    versionFormat = '$versionPrefix.$baseVersionFormat'
                    versionPrefix = "Model"
                }
            }

            distribution {
                explicit = false
                external = true
            }

            "[1.2,)" {
                repositoryType = MERCURIAL
                buildSystem = MAVEN
                vcsUrl = "ssh://hg@mercurial//buildsystem-model"
            }

            build {
                javaVersion = "1.6"
                mavenVersion = "1.6-maven"
                gradleVersion = "1.6-gradle"
                requiredProject = false
                systemProperties = "-D1.6"
                projectVersion = "03.1.6"
                buildTasks = "build"
            }

            branch = "1.6-branch"
            deprecated = true
        }

        "buildsystem-mojo" {
            componentDisplayName = "Buildsystem Mojo"
            artifactId = "buildsystem-maven-plugin"
            vcsUrl = "ssh://hg@mercurial/maven-buildsystem-plugin"
            tag = 'maven-buildsystem-plugin-$version'
            jira {
                majorVersionFormat = '$major.$minor'
                releaseVersionFormat = '$major.$minor.$service'
                component {
                    versionFormat = '$versionPrefix.$baseVersionFormat'
                    versionPrefix = "Mojo"
                }
            }
        }

        notJiraComponent {
            artifactId = "notJiraComponent"
            vcsSettings {
                vcsUrl = "ssh://hg@mercurial//not-jira-component"
                repositoryType = MERCURIAL
            }

            build {
                requiredTools = "BuildEnv"
                buildTasks = "build"
            }

            distribution {
                explicit = true
                external = false
            }
        }

        "sub-component-with-defaults" {
            releaseManager = "anotherreleasemanager"
            securityChampion = "anothersecuritychampion"
            system = "CLASSIC,SIGMA"
            componentDisplayName = "Human readable sub-component-with-defaults name"
            componentOwner = "Another Owner"
            groupId = "org.octopusden.octopus.buildsystem.sub5"
            vcsUrl = "OctopusSource/zenit"
        }
    }
}
