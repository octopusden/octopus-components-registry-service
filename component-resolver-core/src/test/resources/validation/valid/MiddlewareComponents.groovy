import static org.octopusden.octopus.escrow.BuildSystem.*

component_test {
    componentDisplayName = "componentDisplayName"
    componentOwner = "svol"
    releaseManager = "svol"
    securityChampion = "svol"
    groupId = "org.octopusden.octopus.dwh"
    jira {
        projectKey = "COMPONENT"
        lineVersionFormat = '$major02.$minorC'
        majorVersionFormat = '$major02.$minorC.$serviceC'
        releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
        buildVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
    }
    if( System.env.WHISKEY?.contains("ESCROW") ) {
        buildSystem = WHISKEY
        build {
            requiredTools = "BuildEnv,Whiskey,PowerBuilderCompiler170"
        }
    } else {
        buildSystem = PROVIDED;

        vcsSettings {
            externalRegistry = "dwh"
        }
    }

    distribution {
        explicit = true
        external = true
    }
}
