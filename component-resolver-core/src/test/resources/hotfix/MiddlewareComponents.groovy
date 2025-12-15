import static org.octopusden.octopus.escrow.BuildSystem.*

"component_hotfix" {
    componentDisplayName = "Hotfix component"
    componentOwner = "user"
    jira {
        projectKey = "FH"
    }
    buildSystem = GRADLE
    artifactId = "component_hotfix"
    groupId = "org.octopusden.octopus"
    tag = 'component_hotfix-$version'

    vcsSettings {
        vcsUrl = 'git@gitlab:platform/component_hotfix.git'
        hotfixBranch = 'hotfix'
    }

    build {
        javaVersion = 1.8
        gradleVersion = "4.8"
        requiredProject = false
        dependencies {
            autoUpdate = true
        }
    }

    "[1.0,1.0.336)" {
        build {
            buildTasks = "assemble -x checkstyleMain -x findBugsMain -x pmdMain"
        }
    }

    "[1.0.336, 1.0.344]" {
        jira {
            buildVersionFormat = '$major.$minor.$service'
            hotfixVersionFormat = '$major.$minor.$service-$build'
        }
        build {
            gradleVersion = "4.0"
            buildTasks = "assemble -x checkstyleMain -x findBugsMain -x pmdMain"
        }
    }
    "(1.0.344,1.0.570)" {
        build {
            buildTasks = "assemble -x checkstyleMain -x spotBugsMain -x pmdMain"
        }
    }
    "[1.0.570,)" {
        build {
            buildTasks = "assemble"
        }
    }

    distribution {
        explicit = false
        external = true
    }
}
