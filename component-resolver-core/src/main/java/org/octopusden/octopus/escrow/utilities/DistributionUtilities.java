package org.octopusden.octopus.escrow.utilities;

import java.net.URI;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.octopusden.octopus.escrow.dto.DistributionEntity;
import org.octopusden.octopus.escrow.dto.FileDistributionEntity;
import org.octopusden.octopus.escrow.dto.MavenArtifactDistributionEntity;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.stream.Collectors;

public final class DistributionUtilities {
    private static final Pattern MAVEN_GAV_PATTERN = Pattern.compile("^[a-zA-Z0-9_.-]+:[a-zA-Z0-9_.-]+(:[a-zA-Z0-9_.-]+(:[a-zA-Z0-9_.-]+)?)?$");

    private DistributionUtilities() {
    }

    public static Collection<DistributionEntity> parseDistributionGAV(final String distributionGAVAttribute) {
        if (distributionGAVAttribute == null || distributionGAVAttribute.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(distributionGAVAttribute.split("[,|]")).filter(StringUtils::isNotBlank).map(String::trim).map(item -> {
            if (item.startsWith("file:")) {
                URI uri;
                try {
                    uri = URI.create(item);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "Invalid GAV entry: '" + item + "'. Invalid file URI syntax.", e
                    );
                }

                String path = uri.getPath();
                if (StringUtils.isBlank(path) || "/".equals(path)) {
                    throw new IllegalArgumentException(
                            "Invalid GAV entry: '" + item + "'. File URI must point to a concrete path."
                    );
                }

                return new FileDistributionEntity(item);
            }

            if (MAVEN_GAV_PATTERN.matcher(item).matches()) {
                return new MavenArtifactDistributionEntity(item);
            }

            throw new IllegalArgumentException("Invalid GAV entry: '" + item + "'. Expected Maven GAV or file URI.");
        }).collect(Collectors.toList());
    }
}
