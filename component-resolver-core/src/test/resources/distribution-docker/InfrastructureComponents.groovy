import static org.octopusden.octopus.escrow.BuildSystem.*


"test-component" {
    componentOwner = "JamesBond"
    securityChampion = "JamesBond"
    releaseManager = "JamesBond"
    componentDisplayName = "Test component"
    groupId = "org.octopusden.octopus"
    buildSystem = GRADLE
    jira {
        displayName = "Components Registry Test component"
        projectKey = "TEST"
        majorVersionFormat = '$major.$minor'
    }
    vcsSettings {
        vcsUrl = "ssh://git@org.octopusden.octopus/releng/test-component.git"
    }
    distribution {
        explicit = true
        external = true
        docker = 'test-component/image:flavour1,test-component/image:flavour2'
    }
    "[1.0,1.3)" {
        vcsSettings {
            branch = "v1.2"
        }
    }
    "[1.3,)" {
        vcsSettings {
            branch = "master"
        }
    }
}
