import static org.octopusden.octopus.escrow.BuildSystem.*

"test-component-1" {
    componentOwner = "stallman"
    releaseManager = "torvalds,tanenbaum"
    securityChampion = "mitnick"
    componentDisplayName = "Test component 1"
    groupId = "org.octopusden.octopus.test1"
    buildSystem = GRADLE
    jira {
        displayName = "Components Registry Test component 1"
        projectKey = "TEST-1"
        majorVersionFormat = '$major.$minor'
    }
    vcsSettings {
        vcsUrl = "ssh://git@org.octopusden.octopus/releng/test-component-1.git"
    }
    distribution {
        explicit = true
        external = true
        docker = "test-1-image"
    }
}

"test-component-2" {
    componentOwner = "stallman"
    releaseManager = "stallman,torvalds,tanenbaum"
    securityChampion = "mitnick"
    componentDisplayName = "Test component 2"
    groupId = "org.octopusden.octopus.test2"
    buildSystem = GRADLE
    jira {
        displayName = "Components Registry Test component 2"
        projectKey = "TEST-2"
        majorVersionFormat = '$major.$minor'
    }
    vcsSettings {
        vcsUrl = "ssh://git@org.octopusden.octopus/releng/test-component-2.git"
    }
    distribution {
        explicit = true
        external = true
        docker = "test-2-image"
    }
}

"test-component-3" {
    componentOwner = "stallman"
    releaseManager = "torvalds"
    securityChampion = "mitnick"
    componentDisplayName = "Test component 3"
    groupId = "org.octopusden.octopus.test3"
    buildSystem = GRADLE
    jira {
        displayName = "Components Registry Test component 3"
        projectKey = "TEST-3"
        majorVersionFormat = '$major.$minor'
    }
    vcsSettings {
        vcsUrl = "ssh://git@org.octopusden.octopus/releng/test-component-3.git"
    }
    distribution {
        explicit = true
        external = true
        docker = "test-3-image"
    }
}