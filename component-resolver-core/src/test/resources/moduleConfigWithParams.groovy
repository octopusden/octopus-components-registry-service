import static BuildSystem.BS2_0
import static BuildSystem.MAVEN
import static VCS.MERCURIAL

final DEFAULT_TAG = '$module-$version'
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
    jira {
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
        customer {
            versionFormat = '$versionPrefix-$baseVersionFormat'
        }
    }
}

pkgj_version = "3.38.30-0004"

pt_k_packages {
    componentOwner = "user1"
    "[${pkgj_version}]" {
        groupId = "org.octopusden.octopus.ptkmodel2"
        artifactId = "pt_k_packages"
        buildSystem = BS2_0
    }
    jira {
        projectKey = "DDD"
    }
}
