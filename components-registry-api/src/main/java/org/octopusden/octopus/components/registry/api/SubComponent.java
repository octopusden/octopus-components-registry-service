package org.octopusden.octopus.components.registry.api;

import java.util.Map;

public interface SubComponent extends VersionedComponentConfiguration {
    String getName();
    Map<String, VersionedComponentConfiguration> getVersions();
}
