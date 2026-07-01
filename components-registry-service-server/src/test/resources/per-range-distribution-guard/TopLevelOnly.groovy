import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.GIT

// CRS #387 fixture — the P1 must-NOT-fail case. The per-component-only fields
// (explicit / external / securityGroups.read) are declared ONLY at the top level
// with non-default values (true / true / grp-a). The bounded range blocks OMIT
// them and change only per-range distribution data (docker). The loader resolves
// the omitted fields to the inherited base value, so the guard must see them as
// EQUAL to the base and NOT fire — proving detection keys on declared-vs-inherited,
// not resolved-vs-false.

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
        explicit = true
        external = true
        docker = "test/vr"
        securityGroups {
            read = "grp-a"
        }
    }

    // Base block: inherits explicit=true / external=true / read=grp-a.
    "[03.53,03.54)" {
        distribution {
            docker = "test/vr"
        }
    }

    // Changes ONLY the per-range docker image; omits the per-component fields.
    "[03.54,03.55)" {
        distribution {
            docker = "test/vr-next"
        }
    }

    jira {
        projectKey = "VR"
    }
}
