import static BuildSystem.MAVEN
import static VCS.MERCURIAL

enum BuildSystem {
    BS2_0,
    MAVEN
}

enum VCS {
    CVS,
    MERCURIAL
}

Defaults {
    jira {
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
        customer {
            versionFormat = '$versionPrefix-$baseVersionFormat'
        }
    }
}

bcomponent {
    componentOwner = "user1"
    "[1.12.1-150,)" {
        vcsUrl = "ssh://hg@mercurial/bcomponent"
        buildFilePath = "module1/my-pom.xml"
        buildSystem = MAVEN
        repositoryType = MERCURIAL
        groupId = "org.octopusden.octopus.bcomponent"
        artifactId = "builder"
        tag = "$module.$version"
    }
    jira {
        projectKey = "BCOMPONENT"
    }
}

octopuswebapi {
    componentOwner = "user1"
    groupId = "org.octopusden.octopus.octopusweb,org.octopusden.octopus.operations"
    artifactId = "apioperations,operations,internalapioperations"
    buildSystem = MAVEN
    buildFilePath = "octopusweb"
    repositoryType = MERCURIAL
    tag = '$module.$version'
    build {
        javaVersion = "1.8"
        systemProperties = '-Pts.version= '
    }
    "[2.43.3-52],[2.43.3-75],[2.44.1,2.44.1-57),[2.44.2,2.44.3-28),[2.45.0,)" {
        vcsUrl = 'ssh://hg@mercurial/products/wproject/octopusweb'
    }
    jira {
        projectKey = "WCOMPONENT"
    }
}
