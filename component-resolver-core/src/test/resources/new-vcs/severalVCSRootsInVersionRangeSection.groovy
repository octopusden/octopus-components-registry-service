import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.CVS
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL

final DEFAULT_TAG = '$module-$version'
final ANY_ARTIFACT = /[\w-\.]+/

Defaults {
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
    "[03.38.30,)" {
        vcsSettings {
            repositoryType = CVS
            tag = 'TEST_COMPONENT2_$version'
            branch = 'TEST_COMPONENT2_03_38_30'
            cvs1 {
                vcsUrl = "OctopusSource/Octopus/Intranet"
            }
            mercurial1 {
                vcsUrl = "ssh://hg@mercurial/zenit"
                repositoryType = MERCURIAL
                branch = 'default'
            }
        }
    }
    jira {
        projectKey = "TEST_COMPONENT2"
    }
}
