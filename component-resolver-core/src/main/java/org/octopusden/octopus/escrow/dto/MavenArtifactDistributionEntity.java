package org.octopusden.octopus.escrow.dto;

import org.octopusden.octopus.components.registry.api.beans.MavenArtifactDistributionEntityBean;

/**
 * Use {@link org.octopusden.octopus.components.registry.api.distribution.entities.MavenArtifactDistributionEntity}
 */
@Deprecated
public class MavenArtifactDistributionEntity extends MavenArtifactDistributionEntityBean implements DistributionEntity {
    public MavenArtifactDistributionEntity(String gav) {
        super(gav);
    }
}
