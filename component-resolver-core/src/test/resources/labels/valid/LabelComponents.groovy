import static org.octopusden.octopus.escrow.BuildSystem.GRADLE

component_without_labels {
    componentOwner = "user2"
    componentDisplayName = "Component without labels"
    groupId = "org.octopusden.octopus.cwc"

    vcsSettings {
        vcsUrl = "ssh://git@git.someorganisation.com/releng/cwc.git"
    }
    jira {
        projectKey = "RELENG"
        majorVersionFormat = '$major'
        releaseVersionFormat = '$major.$minor'
        component {
            versionPrefix = 'cwc'
        }
    }
    distribution {
        explicit = true
        external = false
    }
}

component_with_labels {
    componentDisplayName = "Component with labels"
    componentOwner = "user"
    jira {
        projectKey = "PLCOMMONS"
    }
    vcsUrl = 'git@gitlab:platform/component_with_labels.git'
    buildSystem = GRADLE
    artifactId = "component_with_labels"
    groupId = "org.octopusden.octopus.platform"
    tag = 'component_with_labels-$version'

    labels = ["Label1", "Label3"]

    build {
        javaVersion = 1.8
        gradleVersion = "4.8"
        requiredProject = false
        dependencies {
            autoUpdate = true
        }
    }

    "[1.0,1.0.336)" {
        build {
            buildTasks = "assemble -x checkstyleMain -x findBugsMain -x pmdMain"
        }
    }

    distribution {
        explicit = false
        external = true
    }
}

component_with_subcomponent_and_labels {
    componentDisplayName = "Component with subcomponent and labels"
    componentOwner = "user"
    vcsUrl = 'git@gitlab:platform/component_with_subcomponent_and_labels.git'
    buildSystem = GRADLE
    artifactId = "component_with_subcomponent_and_labels"
    groupId = "org.octopusden.octopus.platform"
    tag = 'component_with_subcomponent_and_labels-$version'

    jira {
        projectKey = "TEST1"
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
    }

    labels = ["Label1"]

    build {
        javaVersion = 1.8
        gradleVersion = "4.8"
        requiredProject = false
        dependencies {
            autoUpdate = true
        }
    }

    distribution {
        explicit = false
        external = true
    }

    components {
        inner_component_with_labels {
            componentDisplayName = "Inner Component with labels"
            componentOwner = "user"
            vcsUrl = 'git@gitlab:platform/inner_component_with_labels.git'
            buildSystem = GRADLE
            artifactId = "inner_component_with_labels"
            groupId = "org.octopusden.octopus.platform"
            tag = 'inner_component_with_labels-$version'

            jira {
                projectKey = "TEST2"
                majorVersionFormat = '$major.$minor'
                releaseVersionFormat = '$major.$minor.$service'
            }

            labels = ["Label3"]

            build {
                javaVersion = 1.8
                gradleVersion = "4.8"
                requiredProject = false
                dependencies {
                    autoUpdate = true
                }
            }

            distribution {
                explicit = false
                external = true
            }
        }

        inner_component_without_labels {
            componentDisplayName = "Inner Component without labels"
            componentOwner = "user"
            vcsUrl = 'git@gitlab:platform/inner_component_without_labels.git'
            buildSystem = GRADLE
            artifactId = "inner_component_without_labels"
            groupId = "org.octopusden.octopus.platform"
            tag = 'inner_component_without_labels-$version'

            jira {
                projectKey = "TEST3"
                majorVersionFormat = '$major.$minor'
                releaseVersionFormat = '$major.$minor.$service'
            }

            build {
                javaVersion = 1.8
                gradleVersion = "4.8"
                requiredProject = false
                dependencies {
                    autoUpdate = true
                }
            }

            distribution {
                explicit = false
                external = true
            }
        }
    }
}
