import static org.octopusden.octopus.escrow.BuildSystem.BS2_0
import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL

final DEFAULT_TAG = "$module-$version"
final ANY_ARTIFACT = /[\w-\.]+/
final String ALL_VERSIONS = "(,0),[0,)"

Defaults {
    system = "NONE"
    releasesInDefaultBranch = true
    repositoryType = MERCURIAL
    buildSystem = MAVEN;
    tag = DEFAULT_TAG;
    artifactId = ANY_ARTIFACT
}

xmloperations {
    "${ALL_VERSIONS}" {
        buildSystem = BS2_0;
        tag = tag_format("XMLOPERATIONS-R-$version")
    }
}


comgroupmodel {
    "${ALL_VERSIONS}" {
        buildSystem = BS2_0;
        tag = tag_format("COMGROUP-R-$version")
    }
}

private String tag_format(String tag2Transform) {
    tag2Transform.replaceAll("\\.", "-")
}
