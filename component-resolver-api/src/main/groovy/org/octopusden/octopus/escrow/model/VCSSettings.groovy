package org.octopusden.octopus.escrow.model

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import org.octopusden.octopus.escrow.exceptions.ComponentResolverException
import org.apache.commons.lang3.StringUtils

class VCSSettings {

    private final String externalRegistry

    private final Collection<VersionControlSystemRoot> versionControlSystemRoots

    private final String buildWorkingDirectory

    static VCSSettings create(String externalVCSComponentName) {
        new VCSSettings(externalVCSComponentName, [])
    }

    static VCSSettings create(String externalVCSComponentName, List<VersionControlSystemRoot> versionControlSystemRoots) {
        new VCSSettings(externalVCSComponentName, versionControlSystemRoots)
    }

    /** The Build Working Directory (ONB-001) comes from the database only; the Groovy DSL leaves it null. */
    static VCSSettings create(String externalVCSComponentName, List<VersionControlSystemRoot> versionControlSystemRoots,
                              String buildWorkingDirectory) {
        new VCSSettings(externalVCSComponentName, versionControlSystemRoots, buildWorkingDirectory)
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

    private VCSSettings(String externalRegistry, List<VersionControlSystemRoot> versionControlSystemRoots, String buildWorkingDirectory = null) {
        this.externalRegistry = externalRegistry
        this.versionControlSystemRoots = versionControlSystemRoots
        this.buildWorkingDirectory = buildWorkingDirectory
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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    String getBuildWorkingDirectory() {
        return buildWorkingDirectory
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
                Objects.equals(buildWorkingDirectory, that.buildWorkingDirectory)
    }

    int hashCode() {
        return Objects.hash(versionControlSystemRoots, externalRegistry, buildWorkingDirectory)
    }

    @Override
    String toString() {
        return "VCSSettings{" +
                "versionControlSystemRoots=" + versionControlSystemRoots +
                ", externalRegistry=" + externalRegistry +
                ", buildWorkingDirectory=" + buildWorkingDirectory +
                '}';
    }
}
