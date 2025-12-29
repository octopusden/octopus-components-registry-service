"ee-component" {
    system = "CLASSIC"
    componentDisplayName = "EE Component"
    componentOwner = "EE Component Owner"
    releaseManager = "EE Component Release Manager"
    groupId = "corp.domain"
    vcsUrl = "ssh://git@git.domain.corp/ee/ee-component.git"
    solution = true
    copyright = "ee-component-copyright"
    jira {
        projectKey = 'EE'
        lineVersionFormat = '$major02.$minor02'
        majorVersionFormat = '$major02.$minorC.$serviceC'
        releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
        buildVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
        displayName = 'EE Component'
    }
    distribution {
        external = true
        explicit = true
        docker = "test/test-component"
    }
}

"ee-client-specific-component" {
    system = "CLASSIC"
    componentDisplayName = "EE Client Specific Component"
    componentOwner = "EE Component Owner"
    releaseManager = "EE Component Release Manager"
    clientCode = "CLIENT_CODE"
    parentComponent = "ee-component"
    groupId = "corp.domain"
    vcsUrl = "ssh://git@git.domain.corp/ee/ee-client-specific-component.git"
    copyright = "ee-client-specific-component-copyright"
    jira {
        projectKey = 'EEC'
        lineVersionFormat = '$major02.$minor02'
        majorVersionFormat = '$major02.$minorC.$serviceC'
        releaseVersionFormat = '$major02.$minor02.$service02.$fix02'
        buildVersionFormat = '$major02.$minor02.$service02.$fix02-$build'
        displayName = 'EEC Component'
    }
    distribution {
        external = true
        explicit = true
    }
}

"ie-component" {
    componentDisplayName = "IE Component"
    componentOwner = "IE Component Owner"
    releaseManager = "IE Component Release Manager"
    groupId = "corp.domain"
    vcsUrl = "ssh://git@git.domain.corp/ie/ie-component.git"
    jira {
        projectKey = 'IE'
        displayName = 'IE Component'
    }
}

"ei-component" {
    componentDisplayName = "EI Component"
    componentOwner = "EI Component Owner"
    releaseManager = "EI Component Release Manager"
    groupId = "corp.domain"
    vcsUrl = "ssh://git@git.domain.corp/ei/ei-component.git"
    jira {
        projectKey = 'EI'
        displayName = 'EI Component'
    }
    distribution {
        explicit = true
        external = false
        GAV = 'corp.domain:ei-component:zip'
    }
}

"ii-component" {
    componentDisplayName = "II Component"
    componentOwner = "II Component Owner"
    releaseManager = "II Component Release Manager"
    groupId = "corp.domain"
    vcsUrl = "ssh://git@git.domain.corp/ii/ii-component.git"
    jira {
        projectKey = 'II'
        displayName = 'II Component'
    }
    distribution {
        explicit = false
        external = false
    }
}

dependency1 {
    system = "CLASSIC"
    componentDisplayName = "Dependency 1"
    componentOwner = "Dependency Owner"
    releaseManager = "Dependency Manager"
    groupId = "corp.domain"
    vcsUrl = "ssh://git@git.domain.corp/ee/dependency-1.git"
    copyright = "dependency1-copyright"
    jira {
        projectKey = 'DEPS'
    }
    distribution {
        external = true
        explicit = true
    }
}

dependency2 {
    system = "CLASSIC"
    componentDisplayName = "Dependency 2"
    componentOwner = "Dependency Owner"
    releaseManager = "Dependency Manager"
    groupId = "corp.domain"
    vcsUrl = "ssh://git@git.domain.corp/ee/dependency-2.git"
    copyright = "dependency2-copyright"
    jira {
        projectKey = 'DEPS'
    }
    distribution {
        external = true
        explicit = true
    }
}
