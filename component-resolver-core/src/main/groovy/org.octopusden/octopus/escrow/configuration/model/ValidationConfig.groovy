package org.octopusden.octopus.escrow.configuration.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import groovy.transform.TypeChecked

@TypeChecked
class ValidationConfig {
    private final Map<String, DistributionConfig> distribution
    private final Set<String> labels

    @JsonCreator
    ValidationConfig(
            @JsonProperty('distribution') Map<String, DistributionConfig> distribution,
            @JsonProperty('labels') Set<String> labels
    ) {
        this.distribution = distribution
        this.labels = labels
    }

    Map<String, DistributionConfig> getDistribution() {
        return distribution
    }

    Set<String> getLabels() {
        return labels
    }

    @TypeChecked
    private static class DistributionConfig {
        private final List<String> exclude

        @JsonCreator
        DistributionConfig(@JsonProperty('exclude') List<String> exclude) {
            this.exclude = exclude
        }

        List<String> getExclude() {
            return exclude
        }
    }
}
