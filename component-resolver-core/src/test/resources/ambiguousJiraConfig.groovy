import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.BuildSystem.PROVIDED
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL

final DEFAULT_TAG = "$module-$version"
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


bcomponent {
    componentOwner = "user1"
    "(1.0,)" {
        vcsUrl = "ssh://hg@mercurial/bcomponent"
        versionRange = "[1.12.1-150,)"
        groupId = "org.octopusden.octopus.bcomponent"
        artifactId = "builder"
        jira {
            projectKey = "bs-core-jira-new"
            majorVersionFormat = '$major'
            releaseVersionFormat = '$major.$minor'
        }
    }
    jira {
        projectKey = "BCOMPONENT"
    }
}

octopusweb {
    "[2.0,)" {
        groupId = "org.octopusden.octopus.octopusweb"
        artifactId = ANY_ARTIFACT
        buildSystem = PROVIDED

        jiraProjectKey = "WCOMPONENT"
        jiraMajorVersionFormat = '$major.$minor'
        jiraReleaseVersionFormat = '$major.$minor.$service'

        jira {
            projectKey = "WCOMPONENT2"
            majorVersionFormat = '$major'
            releaseVersionFormat = '$major.$minor'
        }

    }
}
