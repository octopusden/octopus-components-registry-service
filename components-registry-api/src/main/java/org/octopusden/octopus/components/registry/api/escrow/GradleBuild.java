package org.octopusden.octopus.components.registry.api.escrow;

import java.util.Collection;

public interface GradleBuild {
    boolean getIncludeTestConfigurations();
    Collection<String> getIncludeConfigurations();
    Collection<String> getExcludeConfigurations();
}
