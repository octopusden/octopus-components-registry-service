import static org.octopusden.octopus.escrow.BuildSystem.PROVIDED
import static org.octopusden.octopus.escrow.RepositoryType.CVS

Defaults {
    system = "NONE"
    releasesInDefaultBranch = true
    solution = false
}

"mudule-dbModel" {
    componentOwner = "user1"
    groupId = "org.octopusden.octopus.mudule"
    artifactId = "dbmodel"

    jira {
        projectKey = "COMPONENTDBM"
        majorVersionFormat = '$major.$minor'
        releaseVersionFormat = '$major.$minor.$service'
    }

    "[2,)" {
        buildSystem = PROVIDED
        vcsSettings {
            branch = 'HEAD'
            tag = 'DBMODEL_${version.replaceAll(\'\\\\.\', \'_\')}'
            repositoryType = CVS
            cvs1 {
                vcsUrl = "OctopusSource/OctopusModule/langs/dbmodel"
            }
            cvs2 {
                vcsUrl = "OctopusSource/OctopusModule/JavaSource/org.octopusden.octopus.octopusmudule.dbmodel"
            }
        }
    }
}
