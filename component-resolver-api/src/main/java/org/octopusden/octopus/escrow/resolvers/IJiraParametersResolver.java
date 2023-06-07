package org.octopusden.octopus.escrow.resolvers;

import org.octopusden.octopus.escrow.JiraProjectVersion;
import org.octopusden.octopus.escrow.NoConfigurationFoundException;
import org.octopusden.octopus.escrow.config.ComponentConfig;
import org.octopusden.octopus.escrow.dto.ComponentArtifactConfiguration;
import org.octopusden.octopus.escrow.model.VCSSettings;
import org.octopusden.octopus.releng.dto.ComponentVersion;
import org.octopusden.octopus.releng.dto.JiraComponent;
import org.apache.maven.artifact.Artifact;

import java.util.Map;

public interface IJiraParametersResolver {

    JiraComponent resolveComponent(Artifact mavenArtifact);

    ComponentVersion getComponentByMavenArtifact(Artifact mavenArtifact);

    JiraComponent resolveComponent(ComponentVersion componentVersion);

    boolean isComponentWithJiraParametersExists(Artifact mavenArtifactParameters);

    boolean isComponentWithJiraParametersExists(ComponentVersion componentVersion);

    ComponentVersion getComponentByJiraProject(JiraProjectVersion jiraProjectVersion) throws NoConfigurationFoundException;

    /**
     * Not nullable
     */
    VCSSettings getVersionControlSystemRootsByJiraProject(JiraProjectVersion jiraProjectVersion);

    ComponentConfig getComponentConfig();

    void reloadComponentsRegistry();

    Map<String, ComponentArtifactConfiguration> getMavenArtifactParameters(String component);
}
