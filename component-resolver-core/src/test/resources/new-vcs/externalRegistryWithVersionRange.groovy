import static org.octopusden.octopus.escrow.BuildSystem.*

Defaults {
    buildSystem = MAVEN
    tag = '$module-$version';
    system = "NONE"
    releasesInDefaultBranch = true
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
    }
}

torpedo {
    componentOwner = "streltsov"
    vcsSettings {
        externalRegistry = "NOT_AVAILABLE"
    }
    buildSystem = ESCROW_NOT_SUPPORTED
    groupId = "org.octopusden.octopus.torpedo"
    artifactId = "myartifct"
    jira {
        projectKey = "PRJ"
    }
    "(,1.2.3)" {
    }
}
