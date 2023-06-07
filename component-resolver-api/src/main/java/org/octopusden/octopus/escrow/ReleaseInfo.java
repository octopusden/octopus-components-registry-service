package org.octopusden.octopus.escrow;

import org.octopusden.octopus.components.registry.api.escrow.Escrow;
import org.octopusden.octopus.escrow.model.BuildParameters;
import org.octopusden.octopus.escrow.model.Distribution;
import org.octopusden.octopus.escrow.model.VCSSettings;

import java.util.Objects;
import java.util.Optional;

public final class ReleaseInfo {

    private static final int MAGIC = 31;

    private final BuildSystem buildSystem;
    private final String buildFilePath;
    private final BuildParameters buildParameters;
    private final boolean deprecated;
    private final VCSSettings vcsSettings;
    private final Distribution distribution;
    private final Escrow escrow;

    private ReleaseInfo(final VCSSettings vcsSettings,
                        final BuildSystem buildSystem,
                        final String buildFilePath,
                        final BuildParameters buildParameters,
                        final boolean deprecated,
                        final Distribution distribution,
                        final Escrow escrow) {
        this.buildSystem = buildSystem;
        this.buildFilePath = buildFilePath;
        this.buildParameters = buildParameters;
        this.deprecated = deprecated;
        this.vcsSettings = vcsSettings;
        this.distribution = distribution;
        this.escrow = escrow;
    }

    public static ReleaseInfo create(final VCSSettings vcsSettings,
                                     final BuildSystem buildSystem,
                                     final String buildFilePath,
                                     final BuildParameters buildParameters,
                                     final Distribution distribution,
                                     final boolean deprecated,
                                     final Escrow escrow) {
        Objects.requireNonNull(buildSystem, "buildSystem type can't be null");
        Objects.requireNonNull(vcsSettings, "vcsRoots type can't be null");
        return new ReleaseInfo(vcsSettings, buildSystem, buildFilePath, buildParameters, deprecated, distribution, escrow);
    }


    public BuildSystem getBuildSystem() {
        return buildSystem;
    }


    public String getBuildFilePath() {
        return buildFilePath;
    }

    public BuildParameters getBuildParameters() {
        return buildParameters;
    }

    public Distribution getDistribution() {
        return distribution;
    }

    public boolean deprecated() {
        return deprecated;
    }

    public VCSSettings getVcsSettings() {
        return vcsSettings;
    }

    public Optional<Escrow> getEscrow() {
        return Optional.ofNullable(escrow);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ReleaseInfo that = (ReleaseInfo) o;
        if (buildSystem != that.buildSystem) {
            return false;
        }

        if (!Objects.equals(vcsSettings, that.vcsSettings)) {
            return false;
        }

        if (!Objects.equals(buildParameters, that.buildParameters)) {
            return false;
        }

        if (!Objects.equals(distribution, that.distribution)) {
            return false;
        }

        if (!Objects.equals(escrow, that.escrow)) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = buildSystem.hashCode();
        result = MAGIC * result + (vcsSettings != null ? vcsSettings.hashCode() : 0);
        result = MAGIC * result + (buildFilePath != null ? buildFilePath.hashCode() : 0);
        result = MAGIC * result + (buildParameters != null ? buildParameters.hashCode() : 0);
        result = MAGIC * result + (distribution != null ? distribution.hashCode() : 0);
        result = MAGIC * result + (escrow != null ? escrow.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "ReleaseInfo{" +
                "buildSystem=" + buildSystem +
                ", buildFilePath='" + buildFilePath + '\'' +
                ", buildParameters=" + buildParameters +
                ", deprecated=" + deprecated +
                ", vcsSettings=" + vcsSettings +
                ", distribution=" + distribution +
                ", escrow=" + escrow +
                '}';
    }
}
