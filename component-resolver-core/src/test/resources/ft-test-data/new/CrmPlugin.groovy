"escrow-generator" {
    componentOwner = "user1"
    vcsUrl = "ssh://hg@mercurial/escrow-generator"
    groupId = "org.octopusden.octopus.escrow"
    jira {
        projectKey = "ECOMPONENT"
        majorVersionFormat = '$major'
        releaseVersionFormat = '$major.$minor'
    }
}

"maven-crm-plugin" {
    componentOwner = "user1"
    vcsUrl = "ssh://hg@mercurial/maven-crm-plugin"
    groupId = "org.octopusden.octopus.ci-its"
    artifactId = "maven-crm-plugin"
    tag = 'maven-crm-plugin-R-$version'
    jira {
        projectKey = "MCOMPONENT"
        majorVersionFormat = '$major'
        releaseVersionFormat = '$major.$minor'
    }
}

"jira-parent" {
    componentOwner = "user1"
    vcsUrl = "ssh://hg@mercurial/jira-parent"
    groupId = "org.octopusden.octopus.jira"
    artifactId = "jira-parent"
    jira {
        projectKey = "JBOM"
    }
}

"ci-its" {
    componentOwner = "user1"
    vcsUrl = "ssh://hg@mercurial/ci-its"
    groupId = "org.octopusden.octopus.ci-its"
    artifactId = "ci-its-api|ci-jira|jira-relnotes-dto|ci-teamcity|version-helper|jira-its|ci-its|ci-crm"
    tag = 'ci-its-R-$version'
    jira {
        projectKey = "PK1"
        majorVersionFormat = '$major'
        releaseVersionFormat = '$major.$minor'
    }
}
