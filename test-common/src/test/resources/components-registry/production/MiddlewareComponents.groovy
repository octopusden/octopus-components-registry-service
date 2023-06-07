import static org.octopusden.octopus.escrow.BuildSystem.*
"SMComponent" {
    componentOwner = "user"
    groupId = "org.octopusden.octopus.platform.smcomponent"
    buildSystem = GRADLE
    vcsUrl = 'ssh://git@github.com/octopusden/octopus.git'
    build {
        dependencies {
            autoUpdate = true
        }
    }
    jira {
        projectKey = "WSM"
    }
}