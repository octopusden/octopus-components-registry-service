package org.octopusden.octopus.components.registry.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.octopusden.octopus.components.registry.api.beans.VersionedComponentConfigurationBean;
import org.octopusden.octopus.components.registry.api.build.Build;
import org.octopusden.octopus.components.registry.api.escrow.Escrow;
import org.octopusden.octopus.components.registry.api.vcs.VersionControlSystem;

import java.util.Collection;

@JsonDeserialize(as = VersionedComponentConfigurationBean.class)
public interface VersionedComponentConfiguration {
    Build getBuild();
    Escrow getEscrow();
    String getGroupId();
    Collection<String> getArtifactIds();
    VersionControlSystem getVcs();
}
