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
        this.labels = labels

        def ee = distribution?.get("ee") as Map
        def exclude = ee?.get("exclude") as Collection<String>
        this.distributionEeExclude = exclude?.toSet()
    }

    Set<String> getDistributionEeExclude() {
        return distributionEeExclude
    }

    Set<String> getLabels() {
        return labels
    }
}
