import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.GIT

// Regression fixture for the loader null-safety fix (parseDistributionSection):
// the component declares NO top-level distribution block, so the per-range default
// Distribution is null, and each range block declares only a per-range marker field
// (docker) while omitting explicit/external. Before the fix, resolving the omitted
// booleans dereferenced a null default and NPE'd at load time. After the fix they
// default to false, both ranges resolve identically, and the guard stays silent.

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

    "[03.53,03.54)" {
        distribution {
            docker = "test/vr"
        }
    }

    "[03.54,03.55)" {
        distribution {
            docker = "test/vr-next"
        }
    }

    jira {
        projectKey = "VR"
    }
}
