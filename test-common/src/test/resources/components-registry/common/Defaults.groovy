import static org.octopusden.octopus.escrow.BuildSystem.*

Defaults {
    buildSystem = PROVIDED
    artifactId = ANY_ARTIFACT
    jira {
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
        buildVersionFormat = '$major.$minor.$service-$fix'
        lineVersionFormat = '$major'
        customer {
            versionFormat = '$versionPrefix-$baseVersionFormat'
        }
    }
    distribution {
        explicit = false
        external = true
    }
}
