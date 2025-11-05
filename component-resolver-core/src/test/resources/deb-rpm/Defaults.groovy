/**
 * @author Phil Gorbachev
 * Created at 27.11.2014 15:52
 */
import static org.octopusden.octopus.escrow.BuildSystem.*
import static org.octopusden.octopus.escrow.RepositoryType.*


Defaults {
    buildSystem = MAVEN;
    system = "NONE"
    releasesInDefaultBranch = true
    solution = false
    repositoryType = GIT
    tag = '$module-$version';
    artifactId = ANY_ARTIFACT
    copyright = "copyrights/companyName1"

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
