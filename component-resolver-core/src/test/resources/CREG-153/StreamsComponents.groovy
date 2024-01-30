import static org.octopusden.octopus.escrow.BuildSystem.*

octopusstreams {
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
        GAV =   "org.octopusden.octopus.octopusstreams:octopusstreams-prophix," +
                "org.octopusden.octopus.octopusstreams:octopusstreams-nexto-bw," +
                "org.octopusden.octopus.octopusstreams:octopusstreams-ifp"
    }
}

"octopusstreams-commons" {
    groupId = "org.octopusden.octopus.octopusstreams"
    artifactId = "octopusstreams-commons,kafka-encryption"
    componentOwner = "user234"
    componentDisplayName = "octopus Streams Commons"
    buildSystem = MAVEN
    jira {
        projectKey = "STREAMS"
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
        displayName = "Streams Commons"
        component {
            versionPrefix = "commons"
            versionFormat = '$versionPrefix-$baseVersionFormat'
        }
    }
    vcsSettings {
        vcsUrl = 'ssh://git@github.com/octopusden/streams/octopusstreams-commons.git'
    }
    build {
        javaVersion = "1.8"
    }
}
