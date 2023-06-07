import static org.octopusden.octopus.escrow.BuildSystem.*

"gradle-staging-plugin" {
    componentOwner = "user"
    componentDisplayName = "Gradle Staging Plugin (archived)"
    groupId = "org.octopusden.octopus.gradle.plugin"
    artifactId = "gradle-staging-plugin"
    buildSystem = GRADLE
    "(,1.200),[2,)" {
        vcsSettings {
            vcsUrl = "ssh://git@github.com:octopusden/octopus-rm-gradle-plugin.git"
            tag = 'release-management-gradle-plugin-$version'
        }
    }
    "[1.200, 2)" {
        vcsSettings {
            vcsUrl = "ssh://git@github.com:octopusden/archive/gradle-staging-plugin.git"
        }
    }
    jira {
        projectKey = "GSP"
        majorVersionFormat = '$major'
        releaseVersionFormat = '$major.$minor'
    }
    distribution {
        explicit = false
        external = false
    }
}
