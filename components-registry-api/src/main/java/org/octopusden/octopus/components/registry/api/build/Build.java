package org.octopusden.octopus.components.registry.api.build;

import org.octopusden.octopus.components.registry.api.build.tools.BuildTool;
import org.octopusden.octopus.components.registry.api.model.Dependencies;

import java.util.Collection;

public interface Build {
    String getJavaVersion();
    Collection<BuildTool> getTools();
    /**
     * Get DSL configured 'dependencies'.
     * @return Returns 'dependencies' section of the component's DSL
     */
    Dependencies getDependencies();

    /**
     * Get build system used to build component.
     * @return Returns component's build system
     */
    BuildSystem getBuildSystem();
}
