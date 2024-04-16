import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL

final DEFAULT_TAG = '$module-$version'
final ANY_ARTIFACT = /[\w-\.]+/

Defaults {
    system = "NONE"
    releasesInDefaultBranch = true
    repositoryType = MERCURIAL
    buildSystem = MAVEN;
    tag = DEFAULT_TAG;
    artifactId = ANY_ARTIFACT
    octopusVersion = "03.49"

    jira {
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
        customer {
            versionFormat = '$versionPrefix-$baseVersionFormat'
        }
    }
}


"test-default" {//null
    componentOwner = "user1"
    vcsUrl = "ssh://hg@mercurial//test-default"
    groupId = "org.octopusden.octopus"
    artifactId = "test-default"
    jira {
        projectKey = "bs-core-jira-new"
        majorVersionFormat = '$major'
        releaseVersionFormat = '$major.$minor'
        buildVersionFormat = '$major.$minor.$build'
    }
}


"root-component" {
    componentOwner = "user1"
    octopusVersion = '$major02.$minor02.$service.$fix.$build04,03.47.$service.$fix.$build04'
    groupId = "org.octopusden.octopus.root-component"

    build {
        javaVersion = "1.7"
        requiredTools = "BuildEnv"
    }

    "(,3.48)" { // 03.47
        octopusVersion = '03.47'
        vcsUrl = "ssh://hg@mercurial//root-component.03.47"
    }

    "[3.48,)" { //$major02.$minor02.$service.$fix.$build04
        vcsUrl = "ssh://hg@mercurial//root-component.03.48"
    }

    jira {
        projectKey = "ROOT"
    }

    components {
        "sub-component-one" { // 03.47
            componentOwner = "user1"
            groupId = "org.octopusden.octopus.sub-component-one"
            octopusVersion = '03.47'
            vcsUrl = "ssh://hg@mercurial/sub-component-one"
        }

        "sub-component-two" {//null
            componentOwner = "user1"
            groupId = "org.octopusden.octopus.sub-component-two"
            vcsUrl = "ssh://hg@mercurial/sub-component-two"
        }
    }
}
