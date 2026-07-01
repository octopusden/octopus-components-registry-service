import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.GIT

// CRS #387 fixture — the "base/first-position block is the divergent one" case.
// The component default is explicit=false; the FIRST declared block sets
// explicit=true, while a LATER block omits it (inherits the default false). The
// two ranges therefore resolve different values for a per-component-only field —
// a genuine per-range divergence the guard must reject regardless of which block
// (first or later) carries the declaration. Locks in that the uniformity check is
// order-independent (it is not anchored to configs.first()).

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
        external = false
        docker = "test/vr"
    }

    // First-declared block DECLARES explicit=true (diverges from the default).
    "[03.53,03.54)" {
        distribution {
            explicit = true
            docker = "test/vr"
        }
    }

    // Later block omits explicit → inherits the default (false). Divergence.
    "[03.54,03.55)" {
        distribution {
            docker = "test/vr"
        }
    }

    jira {
        projectKey = "VR"
    }
}
