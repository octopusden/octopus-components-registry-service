
"component-integration" {
    componentDisplayName = "Component (archived)"
    componentOwner = "user5"
    releaseManager = "user5"
    securityChampion = "user5"
    jira {
        projectKey = "FALC"
    }
    groupId = "org.octopusden.octopus.componentgroup"
    vcsUrl = 'ssh://git@github.com/octopusden/archive/project.git'
    tag = 'componentint-$version'

    distribution {
        explicit = true
        external = true
    }
}

"component-integration-1" {
    componentDisplayName = "Falcon Integration 1 (archived)"
    componentOwner = "user5"
    releaseManager = "user5"
    jira {
        projectKey = "FALC"
    }
    groupId = "org.octopusden.octopus.componentgroup1"
    vcsUrl = 'ssh://git@github.com/octopusden/archive/component-int.git'
    tag = 'componentint-$version'

    distribution {
        explicit = false
        external = true
    }
}

"component-integration-2" {
    componentDisplayName = "Component 2 (archived)"
    componentOwner = "user5"
    releaseManager = "user5"
    jira {
        projectKey = "FALC"
    }
    groupId = "org.octopusden.octopus.componentgroup2"
    vcsUrl = 'ssh://git@github.com/octopusden/archive/component-int.git'
    tag = 'componentint-$version'

    distribution {
        explicit = true
        external = false
    }
}