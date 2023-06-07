import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.BuildSystem.PROVIDED
import static org.octopusden.octopus.escrow.RepositoryType.CVS
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL


final ANY_ARTIFACT = /[\w-\.]+/

Defaults {
    tag = '$module-$version'
    artifactId = ANY_ARTIFACT
}

component_23 {
    componentOwner = "user1"
    groupId = "org.octopusden.octopus.system"
    artifactId = "component_23"
    vcsUrl = "ssh://hg@mercurial/o2/other/component_23"
    buildSystem = MAVEN
    repositoryType = MERCURIAL
    tag = '$module-R-$version'

    jira {
        projectKey = "SYSTEM"
        majorVersionFormat = '$major'
        releaseVersionFormat = '$major.$minor'
    }

    distribution {
        explicit = true
        external = false
    }

    "[1,2]" {
        groupId = "org.octopusden.octopus.component_23"
        artifactId = "component_232"
        vcsUrl = "ssh://hg@mercurial/o2/other/component_232"
        buildSystem = PROVIDED
        repositoryType = CVS
        jira {
            majorVersionFormat = '$major.$minor'
            releaseVersionFormat = '$major.$minor.$service'
        }
    }

    "[10,)" {
    }
}



