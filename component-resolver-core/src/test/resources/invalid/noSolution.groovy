import static org.octopusden.octopus.escrow.BuildSystem.GRADLE
import static org.octopusden.octopus.escrow.RepositoryType.CVS

final ANY_ARTIFACT = /[\w-\.]+/

Defaults {
    system = "NONE"
    repositoryType = CVS
    buildSystem = GRADLE
    tag = '$module-$version'
    artifactId = ANY_ARTIFACT
    releasesInDefaultBranch = true
    jira {
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
    }
}

component1 {
    componentOwner = "user1"
    releaseManager = "user1"
    groupId = "org.octopusden.octopus.component1"
    vcsSettings {
        vcsUrl = "OctopusSource/zenit"
    }
    jira {
        projectKey = "PRJ1"
    }
}
