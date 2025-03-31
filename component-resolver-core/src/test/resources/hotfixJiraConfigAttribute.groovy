import static BuildSystem.MAVEN
import static VCS.MERCURIAL
import static org.octopusden.octopus.escrow.BuildSystem.MAVEN

final DEFAULT_TAG = "$module-$version"
final ANY_ARTIFACT = /[\w-\.]+/

enum BuildSystem {
    BS2_0,
    MAVEN,
    PROVIDED
}

enum VCS {
    CVS,
    MERCURIAL
}


Defaults {
    system = "NONE"
    releasesInDefaultBranch = true
    solution = false
    repositoryType = MERCURIAL
    buildSystem = MAVEN;
    tag = DEFAULT_TAG;
    artifactId = ANY_ARTIFACT
}


"hf-omponent" {
    componentOwner = "user1"
    Mercurial {
        vcsUrl = "ssh://hg@mercurial/bcomponent"
        versionRange = "[1.12.1-150,)"
        groupId = "org.octopusden.octopus.bcomponent"
        artifactId = "builder"
        hotfixBranch = "hotfix"
        jira {
            projectKey = "bs-core-jira-new"
            majorVersionFormat = '$major'
            releaseVersionFormat = '$major.$minor'
            buildVersionFormat = '$major.$minor.$fix'
            hotfixVersionFormat = '$major.$minor.$fix.$build'
        }
    }
}

"hf-omponent-2" {
    groupId = "org.octopusden.octopus.octopusstreams"
    artifactId = "octopusstreams-commons,kafka-encryption"
    componentOwner = "user234"
    componentDisplayName = "octopus Streams Commons"
    buildSystem = MAVEN
    jira {
        projectKey = "STREAMS"
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
        buildVersionFormat = '$major.$minor.$fix'
        hotfixVersionFormat = '$major.$minor.$fix.$build'
        displayName = "Streams Commons"
        component {
            versionPrefix = "commons"
            versionFormat = '$versionPrefix-$baseVersionFormat'
        }
    }
    vcsSettings {
        vcsUrl = 'ssh://git@github.com/octopusden/streams/octopusstreams-commons.git'
        hotfixBranch = "hotfix"
    }
    build {
        javaVersion = "1.8"
    }
}
