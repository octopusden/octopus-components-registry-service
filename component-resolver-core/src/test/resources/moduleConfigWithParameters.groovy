import static BuildSystem.MAVEN
import static VCS.MERCURIAL

final DEFAULT_TAG = "$module-$version"
final ANY_ARTIFACT = /[\w-\.]+/

enum BuildSystem {
    BS2_0,
    MAVEN
}

enum VCS {
    CVS,
    MERCURIAL
}

Defaults {
    system = "NONE"
    repositoryType = MERCURIAL
    buildSystem = MAVEN;
    tag = DEFAULT_TAG;
    artifactId = ANY_ARTIFACT

}

pkgj_version = "3.38.30"

"test-project" {
    "${pkgj_version}" {
        groupId = "org.octopusden.octopus.test"
        artifactId = "test-project"
        vcsUrl = "ssh://hg@mercurial//test-project"
    }
}



