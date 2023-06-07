package org.octopusden.octopus.escrow.dto;

import org.octopusden.octopus.components.registry.api.beans.FileDistributionEntityBean;

/**
 * Use {@link org.octopusden.octopus.components.registry.api.distribution.entities.FileDistributionEntity}
 */
@Deprecated
public class FileDistributionEntity extends FileDistributionEntityBean implements DistributionEntity {
    public FileDistributionEntity(final String distributionItem) {
        super(distributionItem);
    }
}
