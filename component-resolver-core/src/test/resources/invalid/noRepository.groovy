package invalid

bcomponent {
    componentOwner = "user1"
    "(1.0,)" {
        buildSystem = MAVEN
        groupId = "org.octopusden.octopus.bcomponent"
        artifactId = "builder"
        tag = '$module.$version'
        vcsUrl = "ssh://hg@mercurial/bcomponent"
    }
    jira {
        projectKey = "BCOMPONENT"
    }
}
