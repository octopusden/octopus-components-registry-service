package org.octopusden.octopus.components.registry.api.distribution;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.octopusden.octopus.components.registry.api.beans.FileDistributionEntityBean;
import org.octopusden.octopus.components.registry.api.beans.MavenArtifactDistributionEntityBean;

import static com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY;
import static com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME;

@JsonTypeInfo(use = NAME, include = PROPERTY)
@JsonSubTypes({
        @JsonSubTypes.Type(value = FileDistributionEntityBean.class, name = "fileDistribution"),
        @JsonSubTypes.Type(value = MavenArtifactDistributionEntityBean.class, name = "mavenDistribution")
})

public interface DistributionEntity {
}
