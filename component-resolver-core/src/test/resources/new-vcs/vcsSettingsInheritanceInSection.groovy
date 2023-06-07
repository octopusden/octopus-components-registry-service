import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.BuildSystem.PROVIDED
import static org.octopusden.octopus.escrow.RepositoryType.CVS
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL

final DEFAULT_TAG = '$module-$version'
final ANY_ARTIFACT = /[\w-\.]+/
final ALL_VERSIONS = "(,0),[0,)"

Defaults {
    repositoryType = MERCURIAL
    buildSystem = MAVEN
    tag = DEFAULT_TAG
    artifactId = ANY_ARTIFACT
}

component {
    componentOwner = "user1"
    jira {
        projectKey = "DDD"
        majorVersionFormat = '$major02.$minorC.$serviceC'
        releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
        buildVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
    }
    vcsSettings {
        branch = 'TEST_COMPONENT2_03_38_30'
        tag = 'TEST_COMPONENT2_$version'
        repositoryType = CVS
        Crc32Crypt {
            vcsUrl = "OctopusSource/Octopus/Intranet"
        }
        DbJava {
            branch = "default"
            repositoryType = MERCURIAL
            vcsUrl = "ssh://hg@mercurial/products/octopusk/DbJava"
        }
    }

    "$ALL_VERSIONS" {
        buildSystem = PROVIDED;
        groupId = "org.octopusden.octopus.wk,org.octopusden.octopus.wk2"
    }
}




