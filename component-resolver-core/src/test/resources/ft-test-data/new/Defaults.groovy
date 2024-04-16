import static org.octopusden.octopus.escrow.BuildSystem.*
import static org.octopusden.octopus.escrow.RepositoryType.*


Defaults {
    system = "NONE"
    releasesInDefaultBranch = true
    repositoryType = MERCURIAL
    buildSystem = MAVEN;
    tag = '$module-$version';
    artifactId = ANY_ARTIFACT
    build {
        javaVersion = "1.8"
        mavenVersion = "3"
        gradleVersion = "4.0"
    }
    jira {
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
        customer {
            versionFormat = '$versionPrefix-$baseVersionFormat'
        }
    }
}
