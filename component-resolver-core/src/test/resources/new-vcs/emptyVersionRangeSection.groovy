import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.CVS
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL

final DEFAULT_TAG = '$module-$version'
final ANY_ARTIFACT = /[\w-\.]+/

Defaults {
    system = "NONE"
    repositoryType = MERCURIAL
    buildSystem = MAVEN
    tag = DEFAULT_TAG
    artifactId = ANY_ARTIFACT
}

component {
    componentOwner = "user1"
    groupId = "org.octopusden.octopus.wk,org.octopusden.octopus.wk2"
    jira {
        projectKey = "DDD"
        majorVersionFormat = '$major02.$minorC.$serviceC'
        releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
        buildVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
    }
    vcsSettings {
        branch = 'TEST_COMPONENT2_$major02_$minor02_$service02'
        tag = 'TEST_COMPONENT2_$version'
        repositoryType = CVS
        Crc32Crypt {
            vcsUrl = "OctopusSource/Octopus/Intranet"
        }
    }

    "(,3.44.99)" {

    }
    "[3.44.99,)" {
        vcsSettings {
            branch = 'HEAD'
            tag = 'overridden_tag-$version'
            repositoryType = MERCURIAL
        }
    }
}






