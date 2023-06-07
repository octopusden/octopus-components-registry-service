"octopus-parent" {
    componentOwner = "user1"
    "[1.2.0,)" {
        vcsUrl = "ssh://hg@mercurial/maven-parent"
        groupId = "org.octopusden.octopus"
        artifactId = "octopus-parent"
    }
}

"test-project" {
    componentOwner = "user1"
    deprecated = true
    vcsUrl = "ssh://hg@mercurial/test-project"
    tag = "java8-test"
    groupId = "org.octopusden.octopus.test"
    artifactId = "test-project"
    "[10,12)" {
        build {
            javaVersion = "1.8"
        }
    }

    "[13,)" {
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

