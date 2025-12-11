import static org.octopusden.octopus.escrow.BuildSystem.GRADLE

Defaults {
    system = "NONE"
    releasesInDefaultBranch = true
    solution = false
    buildSystem = GRADLE
    tag = '$module-$version'
    jira {
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
    }
}

"component_hotfix_1" {
    componentOwner = "user"
    groupId = "org.octopusden.octopus"
    artifactId = "component_hotfix"
    jira {
        projectKey = "HOTFIX"
    }
    vcsSettings {
        vcsUrl = 'git@gitlab:platform/component_hotfix.git'
        hotfixBranch = 'hotfix'
    }
}

"component_hotfix_2" {
    componentOwner = "user"
    groupId = "org.octopusden.octopus"
    artifactId = "component_hotfix"
    jira {
        projectKey = "HOTFIX"
        hotfixVersionFormat = '$major.$minor-$fix'
    }
    vcsSettings {
        vcsUrl = 'git@gitlab:platform/component_hotfix.git'
        hotfixBranch = 'hotfix'
    }
}

"component_hotfix_3" {
    componentOwner = "user"
    groupId = "org.octopusden.octopus"
    artifactId = "component_hotfix"
    jira {
        projectKey = "HOTFIX"
        hotfixVersionFormat = '$major.$minor.$service-$fix'
        buildVersionFormat = '$major.$minor.$service-$build'
    }
    vcsSettings {
        vcsUrl = 'git@gitlab:platform/component_hotfix.git'
        hotfixBranch = 'hotfix'
    }
}