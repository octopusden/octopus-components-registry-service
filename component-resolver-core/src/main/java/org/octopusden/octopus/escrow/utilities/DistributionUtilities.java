package org.octopusden.octopus.escrow.utilities;

import org.octopusden.octopus.escrow.dto.DistributionEntity;
import org.octopusden.octopus.escrow.dto.FileDistributionEntity;
import org.octopusden.octopus.escrow.dto.MavenArtifactDistributionEntity;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.stream.Collectors;

public final class DistributionUtilities {
    private DistributionUtilities() {
    }

    public static Collection<DistributionEntity> parseDistributionGAV(final String distributionGAVAttribute) {
        if (distributionGAVAttribute == null || distributionGAVAttribute.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(distributionGAVAttribute.split("[,|]"))
                .map(item -> item.startsWith("file:/") ? new FileDistributionEntity(item) : new MavenArtifactDistributionEntity(item))
                .collect(Collectors.toList());
    }
}
