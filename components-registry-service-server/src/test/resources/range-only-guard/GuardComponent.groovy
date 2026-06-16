import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.GIT

// Self-contained V1 DSL fixture for RangeOnlyParityGuardTest.
//
// Shape under test: a component whose top-level `build { javaVersion = "17" }` is the
// component-own DEFAULT, plus a SINGLE version-range block "[1.0.700,)" that overrides
// only `javaVersion` to "21". The V1 loader builds moduleConfigurations ONLY from the
// range block, so versions below 1.0.700 (e.g. 1.0.1) match no module and resolve to
// null — there is no all-versions coverage. The component-own default javaVersion "17"
// is therefore never applied to any version. This guard pins that V1 behaviour so the
// v3 DB-mode resolver (separate fixture in the same test) stays in parity.

final DEFAULT_TAG = '$module-$version'
final ANY_ARTIFACT = /[\w-]+/

Defaults {
    system = "NONE"
    releasesInDefaultBranch = true
    solution = false
    buildSystem = MAVEN
    repositoryType = GIT
    artifactId = ANY_ARTIFACT
    tag = DEFAULT_TAG
    jira {
        majorVersionFormat = '$major'
        releaseVersionFormat = '$major.$minor.$service'
        customer {
            versionFormat = '$versionPrefix-$baseVersionFormat'
        }
    }
}

Tools {
    GuardTool {
        escrowEnvironmentVariable = "GUARD_TOOL"
        targetLocation = "tools/GUARD_TOOL"
        sourceLocation = "env.GUARD_TOOL"
        installScript = "script"
    }
}

guardComponent {
    componentOwner = "user1"
    vcsUrl = "ssh://git@example/guard"
    groupId = "org.octopusden.octopus.guard"

    // Component-own default: applies to all versions in principle, but see below.
    build {
        javaVersion = "17"
        requiredTools = "GuardTool"
    }

    // The ONLY declared version-range block. Overrides javaVersion to 21 and inherits
    // requiredTools = GuardTool from the component-own default.
    "[1.0.700,)" {
        build {
            javaVersion = "21"
        }
    }

    jira {
        projectKey = "GUARD"
    }
}
