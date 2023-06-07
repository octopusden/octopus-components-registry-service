import static org.octopusden.octopus.escrow.BuildSystem.*

"octopus-parent" {
    componentOwner = "user1"
    "[1.2.0,)" {
        vcsUrl = "ssh://hg@mercurial/maven-parent"
        groupId = "org.octopusden.octopus"
        artifactId = "octopus-parent"
    }
}


"escrow-test-project" {
    deprecated = true
    vcsUrl = "ssh://hg@mercurial/test/escrow-test-project"
    tag = "system-config-test"
    groupId = "org.octopusden.octopus.test"
    artifactId = "escrow-test-project"
    buildSystem = MAVEN
    build {
        systemProperties = "-DsystemProperty=hello123"
    }
}


"test-project" {
    deprecated = true
    vcsUrl = "ssh://hg@mercurial/test-project"
    tag = "system-config-test"
    "[10,12)" {
        groupId = "org.octopusden.octopus.test"
        artifactId = "test-project"
        build {
            javaVersion = "1.7"
            requiredProject = true
        }
    }
}

"provided-test-dependency" {
    artifactId = "commoncomponent"
    buildSystem = PROVIDED
    "${ALL_VERSIONS}" {
        groupId = "org.octopusden.octopus.test"
    }
}

"provided-test-archive" {
    "[1.0],[2.0],[3.0]" {
        groupId = "org.octopusden.octopus.escrow.test"
        artifactId = "test-arhive,test-archive2,test-archive3"
        buildSystem = PROVIDED
    }
}

"missing-licenses-properties" {
    "[1.0]" {
        groupId = "org.octopusden.octopus.licensecontrol"
        artifactId = "missing-licenses"
        buildSystem = PROVIDED
    }
}


