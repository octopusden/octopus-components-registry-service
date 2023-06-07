import static org.octopusden.octopus.escrow.BuildSystem.*
import static org.octopusden.octopus.escrow.RepositoryType.*

"multi-vcs-provided" {
    groupId = "org.octopusden.octopus.test.escrow"
    buildSystem = PROVIDED
    vcsSettings {
        vcs1 {
            vcsUrl = "ssh://hg@mercurial/test-project"
            repositoryType = MERCURIAL
            tag = "test-project-1.50"
        }
        vcs2 {
            vcsUrl = "ssh://hg@mercurial/test/escrow-test-project"
            repositoryType = MERCURIAL
            tag = "escrow-test-project-1.5"
        }
    }
}
