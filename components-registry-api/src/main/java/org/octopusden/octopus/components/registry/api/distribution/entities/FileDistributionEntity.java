package org.octopusden.octopus.components.registry.api.distribution.entities;

import org.octopusden.octopus.components.registry.api.distribution.DistributionEntity;

import java.net.URI;
import java.util.Optional;

public interface FileDistributionEntity extends DistributionEntity {
    URI getUri();
    Optional<String> getClassifier();
    Optional<String> getArtifactId();
}
