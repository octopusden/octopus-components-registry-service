import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL

Defaults {
    system = "NONE"
    releasesInDefaultBranch = true
    solution = false
    jira {
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
        customer {
            versionFormat = '$versionPrefix-$baseVersionFormat'
        }
    }
}

component {
    componentOwner = "user"
    releaseManager = "user"
    securityChampion = "user"
    system = "CLASSIC"
    componentDisplayName = "COMPONENT Official Name"
    copyright = 'companyName1'
    doc {
        component = "doc_component"
        majorVersion = "1.2"
    }
    vcsUrl = "ssh://hg@mercurial/component"
    buildSystem = MAVEN
    repositoryType = MERCURIAL
    groupId = "io.bcomponent"
    artifactId = "builder"
    tag = '$module.$version'
    branch = "default"
    build {
        requiredTools = "BuildEnv"
    }
    distribution {
        explicit = true
        external = true
        GAV = "org.octopusden.octopus.bcomponent:builder:war,org.octopusden.octopus.component:builder:jar"
    }
    jira {
        projectKey = "BCOMPONENT"
    }
}

"doc_component" {
    componentOwner = "tech_writer1"
    componentDisplayName = "Component Documentation"
    groupId = "io.bcomponent.docs"
    artifactId = "component-docs"
    tag = '$module.$version'
    branch = "default"
    buildSystem = MAVEN
    repositoryType = MERCURIAL
    vcsUrl = "ssh://hg@mercurial/doc_component"

    distribution {
        explicit = true
        external = false //can be also true
        GAV = "org.company.docs:mycomponent-docs:zip"
    }
    jira {
        projectKey = "BCOMPONENT"
        customer {
            versionPrefix = 'doc'
        }
    }
}
