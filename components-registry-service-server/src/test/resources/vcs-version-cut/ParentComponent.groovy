import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.GIT

// Self-contained V1 DSL fixture for VcsSettingsVersionCutTest.
//
// Shape under test: the release format has more parts than the requested version
// ($major.$minor.$service vs "2.2"), while the major and line formats have fewer ($major).
// Non-strict normalization then matches only the major format and cuts "2.2" to "2"; the
// VCS tag must still be built from "2.2", not from "2" padded to "2.0.0".

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
        lineVersionFormat = '$major'
        releaseVersionFormat = '$major.$minor.$service'
        customer {
            versionFormat = '$versionPrefix-$baseVersionFormat'
        }
    }
}

parentComponent {
    componentOwner = "user1"
    vcsUrl = "ssh://git@example/parent"
    groupId = "org.octopusden.octopus.parent"

    jira {
        projectKey = "PARENT"
    }
}

// The same formats with hotfixes enabled (a hotfix branch is set): a version in the hotfix
// format keeps it, other versions take the build format, a cut version is still kept.
hotfixComponent {
    componentOwner = "user1"
    groupId = "org.octopusden.octopus.hotfix"

    jira {
        projectKey = "HOTFIX"
        hotfixVersionFormat = '$major.$minor.$service-$fix'
    }
    vcsSettings {
        vcsUrl = "ssh://git@example/hotfixComponent"
        hotfixBranch = "hotfix"
    }
}
