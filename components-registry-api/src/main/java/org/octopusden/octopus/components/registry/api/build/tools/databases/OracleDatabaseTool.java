package org.octopusden.octopus.components.registry.api.build.tools.databases;

import org.octopusden.octopus.components.registry.api.enums.OracleDatabaseEditions;

public interface OracleDatabaseTool extends DatabaseTool {
    OracleDatabaseEditions getEdition();
}
