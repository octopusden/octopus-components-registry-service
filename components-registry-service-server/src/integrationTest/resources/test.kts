import org.octopusden.octopus.components.registry.dsl.*

component("test-kotlin-component") {
    groupId = "org.octopusden.octopus.test.kotlin"
    artifactId = "kotlin-test-artifact"
    
    jira {
        projectKey = "TESTKT"
        majorVersionFormat = "\$major.\$minor"
        releaseVersionFormat = "\$major.\$minor.\$service"
    }
}
