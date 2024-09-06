import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.CVS
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL

final DEFAULT_TAG = '$module-$version'
final ANY_ARTIFACT = /[\w-\.]+/

Defaults {
    system = "NONE"
    releasesInDefaultBranch = true
    solution = false
    repositoryType = MERCURIAL
    buildSystem = MAVEN
    tag = DEFAULT_TAG
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
        externalRegistry = "componentc_db"

        branch = 'TEST_COMPONENT2_03_38_30'
        tag = 'TEST_COMPONENT2_$version'
        repositoryType = CVS
        cvs1 {
            vcsUrl = "OctopusSource/Octopus/Intranet"
        }
        cvs2 {
            vcsUrl = "OctopusSource/Octopus/Module2"
        }
        mercurial1 {
            vcsUrl = "ssh://hg@mercurial/zenit"
            repositoryType = MERCURIAL
            branch = "default"
        }
    }
    jira {
        projectKey = "TEST_COMPONENT2"
    }
}
