import static org.octopusden.octopus.escrow.BuildSystem.MAVEN
import static org.octopusden.octopus.escrow.RepositoryType.MERCURIAL

final ANY_ARTIFACT = /[\w-\.]+/

Tools {
    BuildEnv {
        escrowEnvironmentVariable = "BUILD_ENV"
        targetLocation = "tools/BUILD_ENV"
        sourceLocation = '$env.BUILD_ENV'
    }
}

Defaults {
    system = "NONE"
    releasesInDefaultBranch = true
    solution = false
    repositoryType = MERCURIAL
    buildSystem = MAVEN;
    tag = '$module-$version';
    artifactId = ANY_ARTIFACT
    jira {
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
        customer {
            versionFormat = '$versionPrefix-$baseVersionFormat'
        }
    }
    build {
        requiredTools = "BuildEnv"
        javaVersion = "1.8"
        mavenVersion = "3.3.9"
        gradleVersion = "2.10"
    }
}

app {
    componentOwner = "user1"
    vcsUrl = 'ssh://hg@mercurial//server/release'
    groupId = "org.octopusden.octopus.server"
    artifactId = "server"
    components {
        "jdk" {
            componentOwner = "user1"
            groupId = "org.octopusden.octopus.server.jdk"
            buildSystem = MAVEN
            vcsSettings {
                vcsUrl = "ssh://hg@mercurial//server/jdk"
                tag = '$module-$version'
            }
        }
    }
    jira {
        projectKey = "AS"
    }
}
