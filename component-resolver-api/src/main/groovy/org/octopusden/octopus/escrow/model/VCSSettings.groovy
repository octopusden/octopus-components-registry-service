package org.octopusden.octopus.escrow.model

import com.fasterxml.jackson.annotation.JsonIgnore
import org.octopusden.octopus.escrow.exceptions.ComponentResolverException
import org.apache.commons.lang3.StringUtils

class VCSSettings {

    private final String hotfixBranch

    private final String externalRegistry

    private final Collection<VersionControlSystemRoot> versionControlSystemRoots

    static VCSSettings create(String externalVCSComponentName, String hotfixBranch) {
        new VCSSettings(externalVCSComponentName, [], hotfixBranch)
    }

    static VCSSettings create(String externalVCSComponentName, List<VersionControlSystemRoot> versionControlSystemRoots, String hotfixBranch) {
        new VCSSettings(externalVCSComponentName, versionControlSystemRoots, hotfixBranch)
    }

    static VCSSettings create(List<VersionControlSystemRoot> versionControlSystemRoots) {
        new VCSSettings(null, versionControlSystemRoots, null);
    }

    static VCSSettings createForSingleRoot(VersionControlSystemRoot versionControlSystemRoot) {
        new VCSSettings(null, [versionControlSystemRoot], null);
    }

    static VCSSettings createEmpty() {
        new VCSSettings(null, Collections.emptyList(), null);
    }

    private VCSSettings(String externalRegistry, List<VersionControlSystemRoot> versionControlSystemRoots, String hotfixBranch) {
        this.externalRegistry = externalRegistry
        this.versionControlSystemRoots = versionControlSystemRoots
        this.hotfixBranch = hotfixBranch
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

    String getHotfixBranch() {
        hotfixBranch
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
        return Objects.equals(versionControlSystemRoots, that.versionControlSystemRoots) &&
                Objects.equals(externalRegistry, that.externalRegistry) &&
                Objects.equals(hotfixBranch, that.hotfixBranch)
    }

    int hashCode() {
        return Objects.hash(versionControlSystemRoots, externalRegistry, hotfixBranch)
    }

    @Override
    String toString() {
        return "VCSSettings{" +
                "versionControlSystemRoots=" + versionControlSystemRoots +
                ", externalRegistry=" + externalRegistry +
                ", hotfixBranch='" + hotfixBranch + "'" +
                '}';
    }
}
