import static org.octopusden.octopus.escrow.BuildSystem.*
import static org.octopusden.octopus.escrow.RepositoryType.*

Defaults {
    buildSystem = MAVEN
    system = "NONE"
    repositoryType = GIT
    tag = '$module-$version'
    artifactId = ANY_ARTIFACT
    solution = false
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
        mavenVersion = "3.6.3"
        gradleVersion = "LATEST"
    }
    distribution {
        explicit = false
        external = true
        securityGroups {
            read = "Production Security"
        }
    }
}
