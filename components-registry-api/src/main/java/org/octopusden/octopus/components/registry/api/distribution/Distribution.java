package org.octopusden.octopus.components.registry.api.distribution;

import java.util.Collection;

public interface Distribution {
    boolean getExternal();
    boolean getExplicit();
    Collection<DistributionEntity> getEntities();
}
