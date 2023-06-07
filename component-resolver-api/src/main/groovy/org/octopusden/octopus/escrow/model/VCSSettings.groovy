package org.octopusden.octopus.escrow.model

import com.fasterxml.jackson.annotation.JsonIgnore
import org.octopusden.octopus.escrow.exceptions.ComponentResolverException
import org.apache.commons.lang3.StringUtils

class VCSSettings {

    private final String externalRegistry

    private final Collection<VersionControlSystemRoot> versionControlSystemRoots

    static VCSSettings create(String externalVCSComponentName) {
        new VCSSettings(externalVCSComponentName, [])
    }

    static VCSSettings create(String externalVCSComponentName, List<VersionControlSystemRoot> versionControlSystemRoots) {
        new VCSSettings(externalVCSComponentName, versionControlSystemRoots)
    }

    static VCSSettings create(List<VersionControlSystemRoot> versionControlSystemRoots) {
        new VCSSettings(null, versionControlSystemRoots);
    }

    static VCSSettings createForSingleRoot(VersionControlSystemRoot versionControlSystemRoot) {
        new VCSSettings(null, [versionControlSystemRoot]);
    }

    static VCSSettings createEmpty() {
        new VCSSettings(null, Collections.emptyList());
    }

    private VCSSettings(String externalRegistry, List<VersionControlSystemRoot> versionControlSystemRoots) {
        this.externalRegistry = externalRegistry
        this.versionControlSystemRoots = versionControlSystemRoots
    }

    @JsonIgnore
    boolean hasNoConfiguredVCSRoot() {
        return versionControlSystemRoots.isEmpty() || versionControlSystemRoots.size() == 1 && versionControlSystemRoots[0].vcsPath == null
    }

    @JsonIgnore()
    boolean externalRegistry() {
        StringUtils.isNotBlank(externalRegistry)
    }

    @JsonIgnore
    boolean notAvailable() {
        externalRegistry == "NOT_AVAILABLE"
    }

    String getExternalRegistry() {
        externalRegistry
    }

    List<VersionControlSystemRoot> getVersionControlSystemRoots() {
        return versionControlSystemRoots
    }

    @JsonIgnore
    VersionControlSystemRoot getSingleVCSRoot() {
        if (versionControlSystemRoots.isEmpty()) {
            throw new ComponentResolverException("No VCS Roots are defined in the component")
        }
        if (versionControlSystemRoots.size() != 1) {
            throw new ComponentResolverException("Several VCS Roots $versionControlSystemRoots are not supported for the component: ")
        }
        return versionControlSystemRoots[0]
    }

    boolean equals(o) {
        if (this.is(o)) return true
        if (getClass() != o.class) return false

        VCSSettings that = (VCSSettings) o
        if (versionControlSystemRoots != that.versionControlSystemRoots) return false
        if (externalRegistry != that?.externalRegistry) return false

        return true
    }

    int hashCode() {
        int result = 31
        result = result + 31 * versionControlSystemRoots.hashCode()
        if (externalRegistry != null) {
            result = result + 31 * externalRegistry?.hashCode()
        }
        return result
    }

    @Override
    String toString() {
        return "VCSSettings{" +
                "versionControlSystemRoots=" + versionControlSystemRoots +
                ", externalRegistry=" + externalRegistry +
                '}';
    }
}
