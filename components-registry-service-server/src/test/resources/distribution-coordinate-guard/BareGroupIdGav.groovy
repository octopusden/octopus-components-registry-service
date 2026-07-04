import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.GIT

// TD-011 / #349 fixture — a bare-groupId distribution.GAV (no ':'), which the
// import previously silently dropped. The guard must fail loud, naming the
// component + raw entry.
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
        GAV = "org.octopusden.octopus.bare"
    }
    jira { projectKey = "DIST" }
}
