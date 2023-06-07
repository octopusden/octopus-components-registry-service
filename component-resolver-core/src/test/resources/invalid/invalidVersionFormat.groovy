package invalid

import static org.octopusden.octopus.escrow.BuildSystem.PROVIDED

octopusweb {
    "(,0),[0,)" {
        groupId = "org.octopusden.octopus.octopusweb"
        artifactId = "all"
        buildSystem = PROVIDED
        jiraProjectKey = "WCOMPONENT"
        jiraReleaseVersionFormat = 'octopusweb.$major.$minor.$service'
        jiraMajorVersionFormat = 'octopusweb.$major.$minor'
    }
}
