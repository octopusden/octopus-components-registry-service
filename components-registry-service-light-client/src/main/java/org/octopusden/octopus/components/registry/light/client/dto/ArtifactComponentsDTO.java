package org.octopusden.octopus.components.registry.light.client.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collection;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ArtifactComponentsDTO {
    private final Collection<ArtifactComponentDTO> artifactComponents;

    @JsonCreator
    public ArtifactComponentsDTO(@JsonProperty("artifactComponents") Collection<ArtifactComponentDTO> artifactComponents) {
        this.artifactComponents = artifactComponents;
    }

    public Collection<ArtifactComponentDTO> getArtifactComponents() {
        return artifactComponents;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ArtifactComponentsDTO that = (ArtifactComponentsDTO) o;
        return Objects.equals(artifactComponents, that.artifactComponents);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(artifactComponents);
    }

    @Override
    public String toString() {
        return "ArtifactComponentsDTO{artifactComponents=" + artifactComponents + '}';
    }
}