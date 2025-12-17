import static org.octopusden.octopus.escrow.BuildSystem.*
import static org.octopusden.octopus.escrow.RepositoryType.*


Defaults {
    system = "NONE"
    releasesInDefaultBranch = true
    solution = false
    repositoryType = MERCURIAL
    buildSystem = MAVEN;
    tag = '$module-$version';
    jira {
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
        buildVersionFormat = '$major.$minor.$service'
        hotfixVersionFormat = '$major.$minor.$service.$fix.$build'
        customer {
            versionFormat = '$versionPrefix-$baseVersionFormat'
        }
    }
    build {
        requiredTools = "BuildEnv"
        javaVersion = "1.8"
        mavenVersion = "3.3.9"
        gradleVersion = "LATEST"
    }
    distribution {
        explicit = false
        external = true
    }
}
