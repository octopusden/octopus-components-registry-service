import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL

final DEFAULT_TAG = "$module-$version"
final ANY_ARTIFACT = /[\w-\.]+/


Defaults {
    repositoryType = MERCURIAL
    buildSystem = MAVEN;
    tag = DEFAULT_TAG;
    artifactId = ANY_ARTIFACT
    branch = 'default'
}


octopusweb {
    groupId = "org.octopusden.octopus.octopusweb"
    artifactId = ANY_ARTIFACT
    buildSystem = MAVEN
    vcsUrl = "ssh://hg@mercurial/zenit"
    vcsSettings {
        repo1 {
            vcsUrl = "ssh://hg@mercurial//spartak"
        }
    }
}




