package org.octopusden.octopus.escrow.resolvers;

import org.octopusden.octopus.releng.dto.ComponentVersion;
import org.apache.maven.artifact.Artifact;

public interface IModuleByArtifactResolver {
    ComponentVersion resolveComponentByArtifact(Artifact mavenArtifact);
    void reloadComponentsRegistry();
}
