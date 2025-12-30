package org.octopusden.octopus.escrow.utilities;

import org.apache.commons.lang3.StringUtils;
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
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .map(item -> {
                    if (item.startsWith("file:/")) {
                        return new FileDistributionEntity(item);
                    }

                    String[] parts = item.split(":", -1);
                    if (parts.length >= 2 && parts.length <= 4 && Arrays.stream(parts).allMatch(StringUtils::isNotBlank)) {
                        return new MavenArtifactDistributionEntity(item);
                    }

                    throw new IllegalArgumentException(
                            "Invalid GAV entry: '" + item +
                                    "'. Expected 'groupId:artifactId[:...]' or 'file:/<path>'."
                    );
                })
                .collect(Collectors.toList());
    }
}
