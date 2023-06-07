import static org.octopusden.octopus.escrow.BuildSystem.PROVIDED

Defaults {
    jira {
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
        customer {
            versionFormat = '$versionPrefix-$baseVersionFormat'
        }
    }
    distribution {
        explicit = false
        external = true
        securityGroups {
            read = "vfiler1-default-rd"
        }
    }
}

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
            read = "group1"
        }
    }
}

octopusweb {
    componentOwner = "user"
    groupId = "org.octopusden.octopus.octopusweb"
    "(,0),[0,)" {
        groupId = "org.octopusden.octopus.octopusweb"
        artifactId = "octopusweb"
        buildSystem = PROVIDED
        jira {
            projectKey = "WCOMPONENT"
            majorVersionFormat = '$major.$minor'
            releaseVersionFormat = '$major.$minor.$service'
            buildVersionFormat = '$major.$minor.$service.$build'
        }
    }
}