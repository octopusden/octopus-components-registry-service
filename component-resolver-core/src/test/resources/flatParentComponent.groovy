import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL

// Two flat (top-level) components where `childcomp` references `parentcomp` via the
// flat `parentComponent` field. NEITHER owns a `components { }` block, so neither is
// an aggregator — a flat `parentComponent` reference must NOT create aggregator/group
// membership (R1). Used by EscrowConfigurationLoaderTest to prove that
// `aggregatorSubComponents` is derived only from `components { }`, never from
// `parentComponent`.

Defaults {
    system = "NONE"
    releasesInDefaultBranch = true
    solution = false
    copyright = 'companyName1'

    jira {
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
        customer {
            versionFormat = '$versionPrefix-$baseVersionFormat'
        }
    }
}

Tools {
    BuildEnv {
        escrowEnvironmentVariable = "BUILD_ENV"
        targetLocation = "tools/BUILD_ENV"
        sourceLocation = "env.BUILD_ENV"
        installScript = "script"
    }
}

parentcomp {
    componentOwner = "user"
    releaseManager = "user"
    securityChampion = "user"
    system = "CLASSIC"
    componentDisplayName = "Parent Component Official Name"
    "[1.0,)" {
        vcsUrl = "ssh://hg@mercurial/parentcomp"
        buildSystem = MAVEN
        repositoryType = MERCURIAL
        groupId = "org.octopusden.octopus.parentcomp"
        artifactId = "parentcomp"
        tag = '$module.$version'
        branch = "default"
        build {
            requiredTools = "BuildEnv"
        }
        distribution {
            explicit = true
            external = true
            GAV = "org.octopusden.octopus.parentcomp:parentcomp:jar"
        }
        jira {
            projectKey = "PARENTCOMP"
        }
    }
}

childcomp {
    parentComponent = "parentcomp"
    componentOwner = "user"
    releaseManager = "user"
    securityChampion = "user"
    system = "CLASSIC"
    componentDisplayName = "Child Component Official Name"
    "[1.0,)" {
        vcsUrl = "ssh://hg@mercurial/childcomp"
        buildSystem = MAVEN
        repositoryType = MERCURIAL
        groupId = "org.octopusden.octopus.childcomp"
        artifactId = "childcomp"
        tag = '$module.$version'
        branch = "default"
        build {
            requiredTools = "BuildEnv"
        }
        distribution {
            explicit = true
            external = true
            GAV = "org.octopusden.octopus.childcomp:childcomp:jar"
        }
        jira {
            projectKey = "CHILDCOMP"
        }
    }
}
