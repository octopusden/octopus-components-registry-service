package org.octopusden.octopus.components.registry.api.build.tools.oracle;

import org.octopusden.octopus.components.registry.api.build.tools.BuildTool;

public interface OdbcTool extends BuildTool {
    String getVersion();
}
