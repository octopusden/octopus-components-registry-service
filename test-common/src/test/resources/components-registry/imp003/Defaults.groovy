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
    // Non-null buildConfiguration clone-base: EscrowConfigurationLoader.mergeComponents
    // calls buildConfiguration.buildTools.addAll(...) unconditionally when the KTS
    // component has a build block. Without this, components with no Groovy-side
    // build block get buildConfiguration = null after merge, triggering NPE in
    // mergeComponents and silently preventing attachBuildToolBeans from firing.
    build { }
}
