package org.octopusden.octopus.components.registry.api.vcs;


import java.util.Collection;

/**
 * Multiply VCS settings.
 */
public interface MultiplyVersionControlSystem extends VersionControlSystem {
    Collection<VersionControlSystem> getVersionControlSystems();
}
