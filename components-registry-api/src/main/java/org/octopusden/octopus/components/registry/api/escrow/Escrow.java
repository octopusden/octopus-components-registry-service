package org.octopusden.octopus.components.registry.api.escrow;

import java.util.Collection;
import java.util.Optional;

public interface Escrow {
    GradleBuild getGradle();
    String getBuildTask();
    Collection<String> getProvidedDependencies();
    Optional<Long> getDiskSpaceRequirement();

    /**
     * Additional sources that should be included in Escrow package.
     * E.g. node_modules directory.
     * @return Returns collection of additional sources
     */
    Collection<String> getAdditionalSources();

    /**
     * Mark component as ready or not ready for reusing.
     * Reusing means that prepared component's escrow package can be used while creating another escrow component's package if last one depends on it.
     * E.g.
     * Component A is marked as escrow reusable.
     * Component B depends on A.
     * Then component's B escrow process will not build component A to get dependencies (third party). Escrow process will reuse already built component's A escrow package.
     * @return Returns true if component's escrow package can be reused, false otherwise
     */
    boolean isReusable();
}
