import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.GIT

// CRS #387 fixture — a component whose bounded range blocks DECLARE
// distribution.explicit / distribution.external differing from the base
// (first declared block). The import guard must reject this loudly instead of
// silently dropping the per-range values and taking the base.

Defaults {
    system = "NONE"
    releasesInDefaultBranch = true
    solution = false
    buildSystem = MAVEN
    repositoryType = GIT
    artifactId = /[\w-]+/
    tag = '$module-$version'
    jira {
        majorVersionFormat = '$major'
        releaseVersionFormat = '$major.$minor.$service'
        customer {
            versionFormat = '$versionPrefix-$baseVersionFormat'
        }
    }
}

vrComponent {
    componentOwner = "user1"
    vcsUrl = "ssh://git@example/vr"
    groupId = "org.octopusden.octopus.vr"

    distribution {
        explicit = false
        external = true
        docker = "test/vr"
    }

    // Base (first declared block): explicit=false, external=true (inherited).
    "[03.53,03.54)" {
        distribution {
            explicit = false
            external = true
            docker = "test/vr"
        }
    }

    // Diverges on BOTH per-component-only flags — must be rejected.
    "[03.54,03.55)" {
        distribution {
            explicit = true
            external = false
            docker = "test/vr"
        }
    }

    jira {
        projectKey = "VR"
    }
}
