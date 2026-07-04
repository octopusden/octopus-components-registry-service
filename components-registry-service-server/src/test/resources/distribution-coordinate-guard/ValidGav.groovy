import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.GIT

// Well-formed group:artifact[:ext] coordinate — the guard must NOT fire.
Defaults {
    system = "NONE"
    buildSystem = MAVEN
    repositoryType = GIT
    artifactId = /[\w-]+/
    tag = '$module-$version'
    jira {
        majorVersionFormat = '$major'
        releaseVersionFormat = '$major.$minor.$service'
        customer { versionFormat = '$versionPrefix-$baseVersionFormat' }
    }
}

distComponent {
    componentOwner = "user1"
    vcsUrl = "ssh://git@example/dist"
    groupId = "org.octopusden.octopus.dist"
    distribution {
        GAV = "org.octopusden.octopus.dist:builder:war"
    }
    jira { projectKey = "DIST" }
}
