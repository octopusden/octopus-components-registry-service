package invalid

import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.CVS
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL

final DEFAULT_TAG = '$module-$version'
final ANY_ARTIFACT = /[\w-\.]+/

Defaults {
    system = "NONE"
    repositoryType = MERCURIAL
    buildSystem = MAVEN;
    tag = DEFAULT_TAG;
    artifactId = ANY_ARTIFACT
}

component {
    componentOwner = "user1"
    groupId = "org.octopusden.octopus.cproject"
    vcsSettings {
        branch = 'TEST_COMPONENT2_03_38_30'
        tag = 'TEST_COMPONENT2_${version}';
        repositoryType = CVS
        spartak = "loosers"
        cvs1 {
            vcsUrl = "OctopusSource/Octopus/Intranet"
            zenit = "champion"
        }
    }
}





