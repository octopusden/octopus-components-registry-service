import static org.octopusden.octopus.escrow.BuildSystem.GRADLE
import static org.octopusden.octopus.escrow.RepositoryType.CVS
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL

final DEFAULT_TAG = '$module-$version'
final ANY_ARTIFACT = /[\w-\.]+/


Defaults {
    system = "NONE"
    tag = '$module-$version';
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
    groupId = "org.octopusden.octopus.octopusweb,org.octopusden.octopus.operations"
    buildSystem = GRADLE
    vcsSettings {
        externalRegistry = "componentc_db"
        repositoryType = MERCURIAL
        tag = 'octopusweb-$version'
        branch = "default"
        root1 {
            branch = "root1-branch"
            vcsUrl = "root1-url"
        }
        "not-overridden-root" {
            repositoryType = CVS
            branch = "NOT_BRANCH"
            vcsUrl = "NOT_URL"
        }
    }
    "(,3.44.99)" {
        vcsSettings {
            externalRegistry = "component_db_NEW"
            tag = 'PATCHED_$version'
            root1 {
                branch = "patched-root1-branch"
            }
            "new-root" {
                vcsUrl = "new-root-url"
                repositoryType = CVS
            }
        }
    }
    jira {
        projectKey = "TEST_COMPONENT2"
    }
}
