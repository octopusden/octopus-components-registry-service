import static org.octopusden.octopus.escrow.BuildSystem.GRADLE
import static org.octopusden.octopus.escrow.RepositoryType.CVS

final ANY_ARTIFACT = /[\w-\.]+/

Defaults {
    releasesInDefaultBranch = true
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
    system = "INVALID"
    groupId = "org.octopusden.octopus.component1"
    vcsSettings {
        vcsUrl = "OctopusSource/zenit1"
    }
    jira {
        projectKey = "PRJ1"
    }

    components {
        component3 {
            componentOwner = "user1"
            releaseManager = "user1"
            vcsSettings {
                vcsUrl = "OctopusSource/zenit3"
            }
        }
    }
}

component2 {
    componentOwner = "user1"
    releaseManager = "user1"
    groupId = "org.octopusden.octopus.component1"
    vcsSettings {
        vcsUrl = "OctopusSource/zenit2"
    }
    jira {
        projectKey = "PRJ1"
    }
}
