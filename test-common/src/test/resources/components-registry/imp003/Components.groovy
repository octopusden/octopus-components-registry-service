// IMP-003 fixture: Groovy side has NO `build` block — oracle tools come from
// the KTS counterpart exclusively. The absence of a Groovy-side build block
// means buildConfiguration inherits from Defaults (which must be non-null).
// If Defaults.groovy loses its `build { }`, mergeComponents NPEs and the
// component never migrates — turning IMP-003 RED.
"TEST_BUILD_KTS_ONLY" {
    componentOwner = "user1"
    groupId = "org.octopusden.octopus.test.kts.only"
    artifactId = "test-build-kts-only"

    jira {
        projectKey = "TBKO"
    }

    distribution {
        explicit = false
        external = false
    }
}
