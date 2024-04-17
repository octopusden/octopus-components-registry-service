import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.CVS
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL

final DEFAULT_TAG = '$module-$version'
final ANY_ARTIFACT = /[\w-\.]+/

Defaults {
    system = "NONE"
    releasesInDefaultBranch = true
    repositoryType = MERCURIAL
    buildSystem = MAVEN;
    tag = DEFAULT_TAG;
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
    groupId = "org.octopusden.octopus.cproject"
    vcsSettings {
        branch = 'TEST_COMPONENT2_03_38_30'
        tag = 'TEST_COMPONENT2_$version';
        repositoryType = CVS
        vcsUrl = "OctopusSource/Octopus/Intranet"
    }
    jira {
        projectKey = "TEST_COMPONENT2"
    }
}
