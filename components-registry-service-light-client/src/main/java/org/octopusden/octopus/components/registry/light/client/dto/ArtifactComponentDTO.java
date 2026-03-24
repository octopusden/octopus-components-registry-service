package org.octopusden.octopus.components.registry.light.client.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ArtifactComponentDTO {
    private final ArtifactDependency artifact;
    private final VersionedComponent component;

    @JsonCreator
    public ArtifactComponentDTO(
            @JsonProperty("artifact") ArtifactDependency artifact,
            @JsonProperty("component") VersionedComponent component) {
        this.artifact = artifact;
        this.component = component;
    }

    public ArtifactDependency getArtifact() {
        return artifact;
    }

    public VersionedComponent getComponent() {
        return component;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ArtifactComponentDTO that = (ArtifactComponentDTO) o;
        return Objects.equals(artifact, that.artifact) &&
                Objects.equals(component, that.component);
    }

    @Override
    public int hashCode() {
        return Objects.hash(artifact, component);
    }

    @Override
    public String toString() {
        return "ArtifactComponentDTO{" +
                "artifact=" + artifact +
                ", component=" + component +
                '}';
    }
}
