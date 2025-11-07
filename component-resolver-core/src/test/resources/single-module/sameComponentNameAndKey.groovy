import static org.octopusden.octopus.escrow.BuildSystem.*

Defaults {
    system = "NONE"
    releasesInDefaultBranch = true
    solution = false
    copyright = "companyName1"
}

Tools {
    BuildEnv {
        escrowEnvironmentVariable = "BUILD_ENV"
        targetLocation = "tools/BUILD_ENV"
        sourceLocation = "env.BUILD_ENV"
        installScript = "script"
    }

}

bcomponent {
    componentOwner = "user"
    releaseManager = "user"
    securityChampion = "user"
    componentDisplayName = "bcomponent"
    vcsSettings {
        externalRegistry = "NOT_AVAILABLE"
//        vcsSettings {
//            vcsUrl = "ssh://hg@mercurial/releng/client-release-notes-report"
//            repositoryType = MERCURIAL
//        }
    }
    buildSystem = PROVIDED
    groupId = "org.octopusden.octopus.octopus"
    artifactId = "zenit"
    jira {
        projectKey = "PROJECTKEY"
        majorVersionFormat = '$major02.$minor02.$service'
        releaseVersionFormat = '$major02.$minor02.$service.$fix.$build'
        lineVersionFormat = '$major02.$minor02'
    }

    distribution {
        explicit = true
        external = true
        GAV = "org.octopusden.octopus.octopus:zenit"
    }
}
