import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.GIT

// CRS #387 fixture — a bounded range block DECLARES a distribution.securityGroups.read
// differing from the base. securityGroups.read is per-component only; the import
// guard must reject this.

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

    // Base (first declared block): read=grp-a (inherited).
    "[03.53,03.54)" {
        distribution {
            docker = "test/vr"
            securityGroups {
                read = "grp-a"
            }
        }
    }

    // Diverges on securityGroups.read — must be rejected.
    "[03.54,03.55)" {
        distribution {
            docker = "test/vr"
            securityGroups {
                read = "grp-b"
            }
        }
    }

    jira {
        projectKey = "VR"
    }
}
