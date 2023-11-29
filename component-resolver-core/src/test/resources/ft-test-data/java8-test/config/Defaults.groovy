import static org.octopusden.octopus.escrow.BuildSystem.*
import static org.octopusden.octopus.escrow.RepositoryType.*


Defaults {
    system = "NONE"
    repositoryType = MERCURIAL
    buildSystem = MAVEN;
    tag = '$module-$version'
    artifactId = ANY_ARTIFACT
    build {
        mavenVersion = "3"
        gradleVersion = "4.0"
        javaVersion = "1.8"
    }
}
