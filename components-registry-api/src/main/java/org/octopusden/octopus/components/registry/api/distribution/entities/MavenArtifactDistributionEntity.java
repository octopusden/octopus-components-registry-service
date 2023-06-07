package org.octopusden.octopus.components.registry.api.distribution.entities;

import org.octopusden.octopus.components.registry.api.distribution.DistributionEntity;

import java.util.Optional;

public interface MavenArtifactDistributionEntity extends DistributionEntity {
    String getGav();
    String getGroupId();
    String getArtifactId();
    Optional<String> getClassifier();
    Optional<String> getExtension();
}
