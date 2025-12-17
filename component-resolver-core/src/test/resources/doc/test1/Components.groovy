import static org.octopusden.octopus.escrow.BuildSystem.MAVEN

"TEST_COMPONENT3" {
    componentDisplayName = "3-D Secure"
    componentOwner = "user4"
    releaseManager = "user4"
    securityChampion = "user4"
    vcsUrl = 'ssh://hg@mercurial/tdsecure'
    groupId = "org.octopusden.octopus.test"
    tag = 'octopustds-$version'
    doc {
       // component = "doc_mycomponent"
    }
    jira {
        projectKey = "TDS"
    }
    build {
        javaVersion = "1.8"
    }

    "(,1.0.107)" {
    }

    "[1.0.107,)" {
        jira {
            releaseVersionFormat = '$major.$minor.$service-$fix'
        }
        tag = 'tdsecure-$version'
    }
}
