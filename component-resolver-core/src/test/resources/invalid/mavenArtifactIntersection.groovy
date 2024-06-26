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


ANY_ARTIFACT = /[\w-\.]+/

Defaults {
    system = "NONE"
    releasesInDefaultBranch = true
    solution = false
    repositoryType = MERCURIAL
    buildSystem = MAVEN;
    tag = "$module-$version";
    artifactId = ANY_ARTIFACT
    jiraMajorVersionFormat = '$major.$minor'
    jiraReleaseVersionFormat = '$major.$minor.$service'
}



releng {
    componentOwner = "user1"
    vcsUrl = "ssh://hg@mercurial/releng"
    groupId = "org.octopusden.octopus.releng"
    jira {
        projectKey = "RELENG"
        majorVersionFormat = '$major'
        releaseVersionFormat = '$major.$minor'
    }
    components {
        "versions-api" {
            componentOwner = "user1"
            vcsUrl = "ssh://hg@mercurial/versions-api"
            artifactId = "versions-api"
            jira {
                majorVersionFormat = '$major.$minor'
                releaseVersionFormat = '$major.$minor.$service'
                component {
                    versionPrefix = 'versions-api'
                }
            }
        }
    }
}
