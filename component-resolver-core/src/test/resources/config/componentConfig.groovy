package newConfig

import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL

final DEFAULT_TAG = '$module-$version'
final ANY_ARTIFACT = /[\w-\.]+/


Defaults {
    system = "NONE"
    releasesInDefaultBranch = true
    solution = false
    repositoryType = MERCURIAL
    buildSystem = MAVEN;
    tag = DEFAULT_TAG;
    artifactId = ANY_ARTIFACT
    jiraMajorVersionFormat = '$major.$minor'
    jiraReleaseVersionFormat = '$major.$minor.$service'
}



commoncomponent {
    componentOwner = "user1"
    "(10,)" {
        groupId = "org.octopusden.octopus.system"
        artifactId = "commoncomponent,hello,bonjur"
        vcsUrl = "ssh://hg@mercurial/o2/other/commoncomponent"
        zenit = "asd"
        branch = "default"
        tag = "commoncomponent-tag"
        jira {
            projectKey = "system"
            majorVersionFormat = '$major.$minor'
            releaseVersionFormat = '$major.$minor.$service'
            displayName = "clientCustomerNameBank"
            customer {
                versionPrefix = "client"
            }
        }
    }
}

