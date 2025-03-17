import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.GIT

Defaults {
    system = "NONE"
    releasesInDefaultBranch = true
    solution = false
    jira {
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
        customer {
            versionFormat = '$versionPrefix-$baseVersionFormat'
        }
    }
}

commoncomponent {
    componentOwner = "user1"
    buildSystem = MAVEN
    repositoryType = GIT
    groupId = "org.octopusden.octopus.system"
    artifactId = "system"
    tag = '$module-$version'
    vcsUrl = "ssh://hg@mercurial/o2/other/system"
    jira {
        projectKey = "SYSTEM"
        component { versionPrefix = "common" }
    }
    '(,1], [1,2]' {}
    '[2,3]' {
        artifactId = "system2"
    }
    '[3,)' {
        artifactId = "system3"
    }
}
