import static org.octopusden.octopus.escrow.BuildSystem.GRADLE
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL

final DEFAULT_TAG = '$module-$version'
final ANY_ARTIFACT = /[\w-\.]+/


Defaults {
    system = "NONE"
    releasesInDefaultBranch = true
    repositoryType = MERCURIAL
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
    tag = 'octopusweb-$version'
    build {
        javaVersion = "1.8"
        systemProperties = '-Pts.version= '
    }
    "(,3.44.99)" {
        vcsUrl = 'ssh://hg@mercurial/products/wproject/octopusweb_2_44_3'
    }

    jira {
        projectKey = "TEST_COMPONENT2"
    }

}
