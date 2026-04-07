package org.octopusden.octopus.components.registry.light.client.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public class VersionedComponent extends Component {
    private final String version;

    @JsonCreator
    public VersionedComponent(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("version") String version) {
        super(id, name);
        this.version = version;
    }

    public String getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        VersionedComponent that = (VersionedComponent) o;
        return Objects.equals(version, that.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), version);
    }

    @Override
    public String toString() {
        return "VersionedComponent{" +
                "version=" + version +
                ", " + super.toString() +
                '}';
    }
}
