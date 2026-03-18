import static org.octopusden.octopus.escrow.BuildSystem.*
import static org.octopusden.octopus.escrow.RepositoryType.*
import org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode

Defaults {
    buildSystem = MAVEN
    escrow {
        generation = EscrowGenerationMode.AUTO
    }
    repositoryType = GIT
    tag = '$module-$version'
    artifactId = ANY_ARTIFACT
    system = "CLASSIC"
    releasesInDefaultBranch = true
    solution = false
    jira {
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
        hotfixVersionFormat = '$major.$minor.$service-$fix'
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
            read = "default-security-group"
        }
    }
}
