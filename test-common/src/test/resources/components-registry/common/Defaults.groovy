import static org.octopusden.octopus.escrow.BuildSystem.*

Defaults {
    system = "NONE"
    buildSystem = PROVIDED
    artifactId = ANY_ARTIFACT
    jira {
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
        buildVersionFormat = '$major.$minor.$service-$fix'
        hotfixVersionFormat = '$major.$minor.$service-$fix.$build'
        lineVersionFormat = '$major'
        customer {
            versionFormat = '$versionPrefix-$baseVersionFormat'
        }
    }
    distribution {
        explicit = false
        external = true
    }
    // Mirror production Defaults.groovy shape: presence of a `build` block makes
    // `Defaults.buildParameters` non-null so that components without their own
    // `build` block (e.g. cards_db in production) inherit it as the clone-base
    // and the KTS-side `tools { database { oracle { ... } } }` merge has a
    // non-null `buildConfiguration` to clear+addAll into.
    build { }
}
