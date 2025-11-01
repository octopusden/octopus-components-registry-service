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
    copyright = "copyrights/companyName2"

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
