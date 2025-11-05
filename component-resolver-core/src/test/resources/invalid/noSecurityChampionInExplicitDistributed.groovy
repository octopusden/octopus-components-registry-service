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
    teamcityReleaseConfigId = 'bt_${module}_release'
    copyright = "copyrights/companyName1"

    jira {
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
    }
}

component1 {
    componentOwner = "user1"
    releaseManager = "user1"
    securityChampion = "anykov,akerzhakov"
    groupId = "org.octopusden.octopus.component1"
    vcsSettings {
        vcsUrl = "OctopusSource/zenit"
    }
    jira {
        projectKey = "PRJ1"
    }
    distribution {
        explicit = true
        external = true
        GAV = "org.octopusden.octopus.component1:component1"
    }
}

component2 {
    componentOwner = "user1"
    releaseManager = "user1"
    componentDisplayName = "OfficialName"
    groupId = "org.octopusden.octopus.component2"
    vcsSettings {
        vcsUrl = "OctopusSource/zenit"
    }
    jira {
        projectKey = "PRJ2"
    }
    distribution {
        explicit = true
        external = true
        GAV = "org.octopusden.octopus.component1:component1"
    }
}

component3 {
    componentOwner = "user1"
    releaseManager = "user1"
    securityChampion = "anykov,akerzhakov,"
    componentDisplayName = "OfficialName"
    groupId = "org.octopusden.octopus.component2"
    vcsSettings {
        vcsUrl = "OctopusSource/zenit"
    }
    jira {
        projectKey = "PRJ2"
    }
    distribution {
        explicit = true
        external = true
        GAV = "org.octopusden.octopus.component1:component1"
    }
}
