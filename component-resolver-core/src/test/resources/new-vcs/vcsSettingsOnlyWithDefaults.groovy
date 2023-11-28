import static org.octopusden.octopus.escrow.BuildSystem.PROVIDED
import static org.octopusden.octopus.escrow.RepositoryType.CVS

final DEFAULT_TAG = '$module-$version'
final ANY_ARTIFACT = /[\w-\.]+/

Defaults {
    system = "NONE"
    artifactId = ANY_ARTIFACT
    jira {
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
        customer {
            versionFormat = '$versionPrefix-$baseVersionFormat'
        }
    }
}

component {
    componentOwner = "user1"
    buildSystem = PROVIDED
    groupId = "org.octopusden.octopus.wk,org.octopusden.octopus.wk2"
    vcsSettings {
        branch = '$major02_$minor02_$service02'
        tag = 'TEST_COMPONENT2_$version'
        repositoryType = CVS
    }
    jira {
        projectKey = "TEST_COMPONENT2"
    }
}


external_registry {
    componentOwner = "user1"
    buildSystem = PROVIDED
    groupId = "org.octopusden.octopus.external"
    vcsSettings {
        externalRegistry = "dwh"
    }
    jira {
        projectKey = "TEST_COMPONENT2"
    }
}
