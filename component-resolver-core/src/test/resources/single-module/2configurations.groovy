import static org.octopusden.octopus.escrow.BuildSystem.BS2_0
import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL

final DEFAULT_TAG = '$module-$version'
final ANY_ARTIFACT = /[\w-]+/

Defaults {
    system = "NONE"
    releasesInDefaultBranch = true
    jira {
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
        customer {
            versionFormat = '$versionPrefix-$baseVersionFormat'
        }
    }
}

bcomponent {
    componentOwner = "user1"
    componentDisplayName = "BCOMPONENT DISPLAY NAME"

    "[1.12.1-150,)" {
        buildSystem = MAVEN
        vcsUrl = "ssh://hg@mercurial/bcomponent"
        repositoryType = MERCURIAL
        tag = DEFAULT_TAG;
        groupId = "org.octopusden.octopus.bcomponent"
        artifactId = ANY_ARTIFACT
        distribution {
            explicit = false
            external = true
        }
    }

    "(,1.12.1-150)" {
        tag = DEFAULT_TAG;
        groupId = "org.octopusden.octopus.bcomponent"
        artifactId = ANY_ARTIFACT
        branch = "default"
        buildSystem = BS2_0
    }
    jira {
        projectKey = "BCOMPONENT"
    }
}
