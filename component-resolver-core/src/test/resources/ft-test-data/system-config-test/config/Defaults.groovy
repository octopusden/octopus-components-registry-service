import static org.octopusden.octopus.escrow.BuildSystem.*
import static org.octopusden.octopus.escrow.RepositoryType.*


Defaults {
    system = "NONE"
    releasesInDefaultBranch = true
    solution = false
    repositoryType = MERCURIAL
    buildSystem = MAVEN;
    tag = '$module-$version';
    artifactId = ANY_ARTIFACT
    build {
        javaVersion = "1.8"
        mavenVersion = "3"
        gradleVersion = "4.0"
    }
}
