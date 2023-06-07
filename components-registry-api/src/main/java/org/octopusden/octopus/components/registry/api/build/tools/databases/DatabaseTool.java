package org.octopusden.octopus.components.registry.api.build.tools.databases;

import org.octopusden.octopus.components.registry.api.build.tools.BuildTool;
import org.octopusden.octopus.components.registry.api.enums.DatabaseTypes;

public interface DatabaseTool extends BuildTool {
    DatabaseTypes getType();
    String getVersion();
    String getSettingsProperty();
}
