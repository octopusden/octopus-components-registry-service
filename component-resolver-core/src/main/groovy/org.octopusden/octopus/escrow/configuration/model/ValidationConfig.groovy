package org.octopusden.octopus.escrow.configuration.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import groovy.transform.TypeChecked

@TypeChecked
class ValidationConfig {
    private final Set<String> distributionEeExclude
    private final Set<String> labels

    ValidationConfig() {}

    @JsonCreator
    ValidationConfig(
            @JsonProperty('distribution') Map<String, Object> distribution,
            @JsonProperty('labels') Set<String> labels
    ) {
        this.labels = labels?.collect { it.toString() }?.toSet()

        def ee = distribution?.ee
        if (ee != null && ee !instanceof Map) {
            throw new IllegalArgumentException(
                    "Invalid format of 'distribution.ee' in validation-config.yaml. " +
                            "Expected map with key 'exclude'"
            )
        }

        def rawExclude = (ee as Map)?.exclude
        this.distributionEeExclude = parseAndValidateExclude(rawExclude)
    }

    Set<String> getDistributionEeExclude() {
        return distributionEeExclude
    }

    Set<String> getLabels() {
        return labels
    }

    private static Set<String> parseAndValidateExclude(Object rawExclude) {
        if (rawExclude == null) {
            return null
        }

        if (rawExclude instanceof Collection) {
            return rawExclude.collect { it.toString() }.toSet()
        }

        if (rawExclude instanceof String) {
            return [rawExclude].toSet()
        }

        throw new IllegalArgumentException(
                "Invalid format of 'distribution.ee.exclude' in validation-config.yaml. " +
                        "Expected string or list of strings"
        )
    }
}
