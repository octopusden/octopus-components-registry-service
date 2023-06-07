package org.octopusden.octopus.escrow.dto;

import org.octopusden.octopus.releng.dto.ComponentVersion;

import java.util.Objects;
import java.util.Optional;

/**
 * Escrow component DTO.
 * Specify component attributes (name, version and etc) to build escrow.
 * NOTE: better place for this class is in Escrow Generator?
 */
public final class EscrowComponentVersion {
    private final String component;
    private final String version;
    private final  String vcsRevision;

    public EscrowComponentVersion(final ComponentVersion componentVersion) {
        this(componentVersion.getComponentName(), componentVersion.getVersion(), null);
    }

    /**
     * Create escrow component.
     * @param component component key mapped to Components Registry
     * @param version version of the component
     * @param vcsRevision VCS revision, could be null, empty or {@link String} value. See {@link #getVcsRevision()} for
     */
    public EscrowComponentVersion(final String component, final String version, final String vcsRevision) {
        Objects.requireNonNull(component, "Component is mandatory");
        Objects.requireNonNull(version, "Version is mandatory");
        this.component = component;
        this.version = version;
        this.vcsRevision = vcsRevision;
    }

    /**
     * Get VCS revision to use in Escrow.
     * @return Return VCS revision
     */
    public Optional<String> getVcsRevision() {
        return Optional.ofNullable(vcsRevision);
    }

    public ComponentVersion toComponentVersion() {
        return ComponentVersion.create(component, version);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final EscrowComponentVersion that = (EscrowComponentVersion) o;
        return component.equals(that.component) &&
                version.equals(that.version) &&
                Objects.equals(vcsRevision, that.vcsRevision);
    }

    @Override
    public int hashCode() {
        return Objects.hash(component, version, vcsRevision);
    }

    @Override
    public String toString() {
        return toComponentVersion() +
                (vcsRevision != null ?  ":" + vcsRevision : "");
    }
}
