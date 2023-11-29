import static org.octopusden.octopus.escrow.BuildSystem.BS2_0
import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL

final DEFAULT_TAG = "$module-$version"
final ANY_ARTIFACT = /[\w-\.]+/

Defaults {
    system = "NONE"
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

system {
    componentOwner = "user1"
    "(,1.0.37-0000)" {
        buildSystem = BS2_0;
        tag = tag_format("SYSTEM-R-$version")
        groupId = "org.octopusden.octopus.system"
        artifactId = "component_23"
    }
    jira {
        projectKey = "SYSTEM"
    }
}

component_23 {
    componentOwner = "user1"
    "[10.0.0,)" {
        groupId = "org.octopusden.octopus.system"
        artifactId = "component_23"
        vcsUrl = "ssh://hg@mercurial/o2/other/component_23"
    }
    jira {
        projectKey = "SYSTEM"
    }
}


private String tag_format(String tag2Transform) {
    tag2Transform.replaceAll("\\.", "-")
}



