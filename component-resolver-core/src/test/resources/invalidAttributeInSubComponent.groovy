import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL

bcomponent {
    componentOwner = "user1"
    buildSystem = MAVEN
    repositoryType = MERCURIAL
    vcsUrl = "ssh://hg@mercurial/bcomponent"
    groupId = "org.octopusden.octopus.bcomponent"
    tag = "$module-R-$version"
    artifactId = "bcomponent"

    jira {
        projectKey = "BCOMPONENT"
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service-$fix'
    }

    components {
        "buildsystem-model" {
            groupId = "org.octopusden.octopus.buildsystem.model"
            artifactId = /[\w-\.]+/
            jira {
                majorVersionFormat = 'Model.$major.$minor'
                releaseVersionFormat = 'Model.$major.$minor.$service'
            }

            "[1.2]" {
                vcsUrl = "ssh://hg@mercurial//buildsystem-model"
            }
        }

        "buildsystem-mojo" {
            artifactId = "buildsystem-maven-plugin"
            vcsUrl = "ssh://hg@mercurial/maven-buildsystem-plugin"
            tag = "maven-buildsystem-plugin-$version"
            jira {
                majorVersionFormat = 'Mojo.$major.$minor'
                releaseVersionFormat = 'Mojo.$major.$minor.$service'
            }
        }

        notJiraComponent {
            zenit = "champion"
            artifactId = "notJiraComponent"
            vcsUrl = "ssh://hg@mercurial//not-jira-component"
            jira {
                abwgd = "22"
            }
        }
    }
}
