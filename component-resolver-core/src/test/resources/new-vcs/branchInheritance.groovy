import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.GIT

final DEFAULT_TAG  = '$module-$version'
final ANY_ARTIFACT = /[\w-\.]+/

Defaults {
    system = "NONE"
    releasesInDefaultBranch = true
    solution = false
    repositoryType = GIT
    buildSystem = MAVEN
    tag = DEFAULT_TAG
    artifactId = ANY_ARTIFACT
}

component {
    componentOwner = "user1"
    groupId = "org.octopusden.octopus.branchInheritance"
    artifactId = "main-component"
    jira {
        projectKey = "TEST"
        component  { versionPrefix = 'MAIN' }
        majorVersionFormat = '$major.$minor.$service'
        releaseVersionFormat = '$major.$minor'
        buildVersionFormat = '$major.$minor.$service.$fix'
    }
    vcsSettings {
        vcsUrl = "ssh://git@github.com"
        branch = 'TEST_COMPONENT_$major.$minor.$service'
        tag = 'TEST_COMPONENT_$version'
    }
    components {
        "sub-component-1" {
            groupId = "org.octopusden.octopus.branchInheritance"
            artifactId = "subcomponent1"
            jira { component { versionPrefix = 'SUB1' } }
            vcsSettings {
                vcsUrl = "ssh://git@github.com/sub1"
            }
        }
        "sub-component-2" {
            groupId = "org.octopusden.octopus.branchInheritance"
            artifactId = "subcomponent2"
            jira { component { versionPrefix = 'SUB2' } }
            vcsSettings {
                vcsUrl = "ssh://git@github.com/sub2"
                branch = 'master'
            }
        }
    }
}
