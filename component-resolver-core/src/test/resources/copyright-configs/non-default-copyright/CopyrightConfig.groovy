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
