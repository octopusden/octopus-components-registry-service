import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL

final DEFAULT_TAG = "$module-$version"
final ANY_ARTIFACT = /[\w-\.]+/

Defaults {
    system = "NONE"
    releasesInDefaultBranch = true
    solution = false
    artifactId = ANY_ARTIFACT
    tag = DEFAULT_TAG
    jira {
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
        customer {
            versionFormat = '$versionPrefix-$baseVersionFormat'
        }
    }
}

"DBSchemeManager-client" {
    componentOwner = "user1"
    "(,0),[0,)" {
        groupId = "org.octopusden.octopus.dbsm"
        artifactId = "component_client,component_client-library,dbsm-ant-task,dbsm-maven-plugin"
        vcsUrl = "ssh://hg@mercurial//DBSchemeManager-client"
        buildSystem = MAVEN
        repositoryType = MERCURIAL
    }
    jira {
        projectKey = "DBSM"
        component { versionPrefix = "client" }
    }
}

DBSchemeManager {
    componentOwner = "user1"
    "(,0),[0,)" {
        groupId = "org.octopusden.octopus.dbsm"
        artifactId = "some-artifact"
        vcsUrl = "ssh://hg@mercurial//DBSchemeManager"
        buildSystem = MAVEN
        repositoryType = MERCURIAL
    }
    jira {
        projectKey = "DBSM"
    }
}
