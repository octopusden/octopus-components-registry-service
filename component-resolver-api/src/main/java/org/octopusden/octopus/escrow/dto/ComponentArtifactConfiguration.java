package org.octopusden.octopus.escrow.dto;

public class ComponentArtifactConfiguration {
    private final String groupPattern;
    private final String artifactPattern;

    public ComponentArtifactConfiguration(final String groupPattern, final String artifactPattern) {
        this.groupPattern = groupPattern;
        this.artifactPattern = artifactPattern;
    }

    public String getGroupPattern() {
        return groupPattern;
    }

    public String getArtifactPattern() {
        return artifactPattern;
    }
}
