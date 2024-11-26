component1 {
    componentDisplayName = "component1"
    componentOwner = "user1"
    releaseManager = "user1"
    securityChampion = "user1"

    groupId = "org.octopusden.octopus.component1"

    jira {
        projectKey = "PROJECT"
        component {
            versionPrefix = "versionPrefix"
        }
    }
    vcsSettings {
        vcsUrl = "ssh://git@github.com/octopusden/component1.git"
        branch = 'component1-$major.$minor'
    }
}

component2 {
    componentDisplayName = "component2"
    componentOwner = "user2"
    releaseManager = "user2"
    securityChampion = "user2"

    groupId = "org.octopusden.octopus.component2"

    jira {
        projectKey = "PROJECT"
        component {
            versionPrefix = "versionPrefix2"
        }
    }
    vcsSettings {
        vcsUrl = "ssh://git@github.com/octopusden/component2.git"
        branch = 'component2-$major.$minor'
    }

    "[1.0, 2.0)" {
        branch = 'component21-$major.$minor'
        jira {
            customer {
                versionPrefix = "newVersionPrefix2"
            }
        }
    }

    "[2.0, 3.0)" {
        branch = 'component22-$major.$minor'
        jira {
            customer {
                versionPrefix = "newVersionPrefix2"
            }
        }
    }
}

component3 {
    componentDisplayName = "component3"
    componentOwner = "user3"
    releaseManager = "user3"
    securityChampion = "user3"

    groupId = "org.octopusden.octopus.component3"

    jira {
        projectKey = "PROJECT"
        component {
            versionPrefix = "versionPrefix3"
        }
    }
    vcsSettings {
        vcsUrl = "ssh://git@github.com/octopusden/component3.git"
        branch = 'component3-$major.$minor'
    }

    "[1.0, 2.0)" {
        jira {
            customer {
                versionPrefix = "versionPrefix"
            }
        }
    }
}

component4 {
    componentDisplayName = "component4"
    componentOwner = "user4"
    releaseManager = "user4"
    securityChampion = "user4"

    groupId = "org.octopusden.octopus.component4"

    jira {
        projectKey = "PROJECT_2"
    }
    vcsSettings {
        vcsUrl = "ssh://git@github.com/octopusden/component4.git"
        branch = 'component4-$major.$minor'
    }
}

component5 {
    componentDisplayName = "component5"
    componentOwner = "user5"
    releaseManager = "user5"
    securityChampion = "user5"

    groupId = "org.octopusden.octopus.component5"

    jira {
        projectKey = "PROJECT_2"
    }
    vcsSettings {
        vcsUrl = "ssh://git@github.com/octopusden/component5.git"
        branch = 'component5-$major.$minor'
    }
}
