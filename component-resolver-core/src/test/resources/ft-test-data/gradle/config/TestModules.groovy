import static org.octopusden.octopus.escrow.BuildSystem.*


"test-project" {
    componentOwner = "user1"
    vcsUrl = "ssh://hg@mercurial/test-project"
    buildSystem = GRADLE;
    groupId = "org.octopusden.octopus.test"
    artifactId = "test-project"
    build {
        javaVersion = "1.7"
        mavenVersion = "3.3.9"
    }
    "[10,12)" {
        tag = "gradle-branch"
    }

    "[13,)" {
        tag = "gradle-overridden-branch"
    }
}


"test-project-2" {
    componentOwner = "user1"
    vcsUrl = "ssh://hg@mercurial/test-project"
    buildSystem = GRADLE;
    groupId = "org.octopusden.octopus.test2"
    artifactId = "test-project2|test-test-project2"
    "[10,)" {
        tag = "gradle-branch-2"
    }

    "[9]" {
        tag = "UNEXISTING_TAG"
    }
    build {
        mavenVersion = "3"
    }

}

"test-gradle-multiproject" {
    componentOwner = "user1"
    vcsUrl = "ssh://hg@mercurial/test-project"
    buildSystem = GRADLE;
    groupId = "org.octopusden.octopus.escrow.test.multiproject"
    "[10,)" {
        tag = "gradle-multiproject-branch"
    }

}



"provided-test-dependency" {
    componentOwner = "user1"
    artifactId = "commoncomponent"
    buildSystem = PROVIDED
    "${ALL_VERSIONS}" {
        groupId = "org.octopusden.octopus.test"
    }
}


"pt_k_packages" {
    componentOwner = "user1"
    "[${pkgj_version}]" {
        groupId = "org.octopusden.octopus.ptkmodel2"
        artifactId = "pt_k_packages,general_util,general_util_dep,thirdparty,generated_base," +
                "K,oracle_i18n_10_2,oracle_jdbc_10_2,pkgj_base,pkgj_base_oracle,sqlj_runtime,k_xmlmanager_metadata,tabedit"
        buildSystem = PROVIDED
    }
}

