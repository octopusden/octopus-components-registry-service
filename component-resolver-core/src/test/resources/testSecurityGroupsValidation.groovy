import static org.octopusden.octopus.escrow.BuildSystem.PROVIDED


"mudule-dbModel" {
    componentOwner = "user1"
    groupId = "org.octopusden.octopus.mudule"
    artifactId = "dbmodel"

    buildSystem = PROVIDED

    jira {
        projectKey = "COMPONENTDBM"
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
    }
    vcsSettings {
        vcsUrl = "git@github.com/octopusden/wproject/mudule.git"
    }

    distribution {
        securityGroups {
            read = "group1 ,"
        }
    }
}
