import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.GIT

// A file://|http(s) URL in GAV is a fileUrl artifact, NOT a Maven coordinate —
// the guard must skip it (no ':' split applies) and NOT fire.
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
        GAV = "file:///as-1.6"
    }
    jira { projectKey = "DIST" }
}
