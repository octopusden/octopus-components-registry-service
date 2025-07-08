import static org.octopusden.octopus.escrow.BuildSystem.GRADLE
import static org.octopusden.octopus.escrow.RepositoryType.CVS

final ANY_ARTIFACT = /[\w-\.]+/

Defaults {
    system = "NONE"
    releasesInDefaultBranch = true
    solution = false
    repositoryType = CVS
    buildSystem = GRADLE
    tag = '$module-$version'
    artifactId = ANY_ARTIFACT
    jira {
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
    }
}

component1 {
    componentOwner = "user1"
    releaseManager = "user1"
    securityChampion = "user1"
    componentDisplayName = "Test component 1"
    groupId = "org.octopusden.octopus.component1"
    vcsSettings {
        vcsUrl = "OctopusSource/zenit"
    }
    jira {
        projectKey = "SYSTEM"
    }
    distribution {
        explicit = true
        external = true
        docker = 'test-component/image:amd64'
    }

}

component2 {
    componentOwner = "user2"
    releaseManager = "user2"
    securityChampion = "user2"
    componentDisplayName = "Test component 2"
    groupId = "org.octopusden.octopus.component2"
    vcsSettings {
        vcsUrl = "OctopusSource/spartak"
    }
    jira {
        projectKey = "HM"
    }
    distribution {
        explicit = true
        external = true
        docker = 'test-component/image:flavour1'
    }
}
