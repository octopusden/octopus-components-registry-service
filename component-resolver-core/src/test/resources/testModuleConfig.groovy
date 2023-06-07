import static BuildSystem.MAVEN
import static VCS.MERCURIAL

final DEFAULT_TAG = "$module-$version"
final ANY_ARTIFACT = /[\w-\.]+/
final String ALL_VERSIONS = "(,0),[0,)"

enum BuildSystem {
    BS2_0,
    MAVEN
}

enum VCS {
    CVS,
    MERCURIAL
}

Defaults {
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

bcomponent {
    componentOwner = "user1"
    "[1.12.1-150,)" {
        vcsUrl = "ssh://hg@mercurial/bcomponent"
        buildSystem = MAVEN
        repositoryType = MERCURIAL
        groupId = "org.octopusden.octopus.bcomponent"
        artifactId = ANY_ARTIFACT
    }

    "(,1.12.1-150)" {
        buildSystem = MAVEN;
        groupId = "org.octopusden.octopus.bcomponent"
        artifactId = "bcomponent2"
        vcsUrl = "OctopusSource/BuildSystem"
        tag = '$module.$version'
    }
    jira {
        projectKey = "BCOMPONENT"
    }
}

"component_apps" {
    componentOwner = "user1"
    "${ALL_VERSIONS}" {
        groupId = "org.octopusden.octopus.group,org.octopusden.octopus.comgroup"
        artifactId = ANY_ARTIFACT
        buildSystem = MAVEN
        vcsUrl = "ssh://hg@mercurial//hm"
        repositoryType = MERCURIAL
    }
    jira {
        projectKey = "HM"
    }
}

comgroupmodel {
    componentOwner = "user1"
    "${ALL_VERSIONS}" {
        buildSystem = BuildSystem.BS2_0;
        tag = tag_format("COMGROUP-R-$version")
    }
}

private String tag_format(String tag2Transform) {
    tag2Transform.replaceAll("\\.", "-")
}
