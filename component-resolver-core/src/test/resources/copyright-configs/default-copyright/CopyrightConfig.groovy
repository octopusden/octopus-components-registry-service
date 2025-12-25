import static org.octopusden.octopus.escrow.BuildSystem.GRADLE

component_without_copyright {
    componentOwner = "user2"
    componentDisplayName = "Component without copyright"
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

component_with_copyright {
    componentDisplayName = "Component with copyright"
    componentOwner = "user"
    jira {
        projectKey = "PLCOMMONS"
    }
    vcsUrl = 'git@gitlab:platform/component_with_copyright.git'
    buildSystem = GRADLE
    artifactId = "component_with_copyright"
    groupId = "org.octopusden.octopus.platform"
    tag = 'component_with_copyright-$version'
    copyright = 'companyName2'

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

component_with_version_copyright {
    componentOwner = "user"
    jira {
        projectKey = "TEST2"
    }
    vcsUrl = 'git@gitlab:platform/component_with_version_copyright.git'
    buildSystem = GRADLE
    artifactId = "component_with_version_copyright"
    groupId = "org.octopusden.octopus.platform"
    tag = 'component_with_version_copyright-$version'

    build {
        javaVersion = 1.8
        gradleVersion = "4.8"
        requiredProject = false
        dependencies {
            autoUpdate = true
        }
    }

    "[2.0, 3.0)" {
        componentDisplayName = "First version"
        branch = 'component21-$major.$minor'
        jira {
            customer {
                versionPrefix = "newVersionPrefix2"
            }
        }
    }

    "[3.0, 4.0)" {
        componentDisplayName = "Second version"
        copyright = 'companyName3'
        jira {
            customer {
                versionPrefix = "newVersionPrefix3"
            }
        }
    }

    distribution {
        explicit = false
        external = true
    }
}

component_with_subcomponent_copyright {
    componentDisplayName = "Component with subcomponent copyright"
    componentOwner = "user"
    jira {
        projectKey = "ANOTHER_TEST"
    }
    vcsUrl = 'git@gitlab:platform/component_with_subcomponent_copyright.git'
    buildSystem = GRADLE
    artifactId = "component_with_subcomponent_copyright"
    groupId = "org.octopusden.octopus.platform"
    tag = 'component_with_subcomponent_copyright-$version'
    copyright = 'companyName2'

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
        inner_component_with_copyright {
            componentDisplayName = "Inner Component with copyright"
            componentOwner = "user"
            jira {
                projectKey = "TEST"
            }
            vcsUrl = 'git@gitlab:platform/inner_component_with_copyright.git'
            buildSystem = GRADLE
            artifactId = "inner_component_with_copyright"
            groupId = "org.octopusden.octopus.platform"
            tag = 'inner_component_with_copyright-$version'
            copyright = 'companyName3'

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

ee_component_without_copyright {
    componentDisplayName = "Streams"
    componentOwner = "user123"
    releaseManager = "user6"
    securityChampion = "user7"

    groupId = "org.octopusden.octopus.octopusstreams"

    jira {
        displayName = "Streams"
        projectKey = "STREAMS"
    }
    vcsSettings {
        vcsUrl = "ssh://git@github.com/octopusden/streams/octopusstreams.git"
        branch = 'octopusstreams-$major.$minor'
    }

    distribution {
        explicit = true
        external = true
        GAV =   "org.octopusden.octopus.octopusstreams:octopusstreams-artifact"
    }
}
