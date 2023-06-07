package org.octopusden.octopus.crs.resolver.core.converter;

import org.octopusden.octopus.components.registry.api.beans.BuildBean;
import org.octopusden.octopus.components.registry.api.beans.ClassicBuildSystem;
import org.octopusden.octopus.components.registry.api.build.Build;
import org.octopusden.octopus.components.registry.api.enums.BuildSystemType;
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig;

public final class BuildParametersConverter extends AbstractConverter<EscrowModuleConfig, Build> {
    public BuildParametersConverter() {
        super(escrowModuleConfig -> {
            final BuildBean build = new BuildBean();
            build.setJavaVersion(escrowModuleConfig.getBuildConfiguration().getJavaVersion());
            if (escrowModuleConfig.getBuildSystem() != null) {
                final ClassicBuildSystem buildSystem = new ClassicBuildSystem(BuildSystemType.valueOf(BuildSystemType.class, escrowModuleConfig.getBuildSystem().toString()));
                switch (escrowModuleConfig.getBuildSystem()) {
                    case GRADLE:
                        if (escrowModuleConfig.getBuildConfiguration().getGradleVersion() != null) {
                            buildSystem.setBuildSystemVersion(escrowModuleConfig.getBuildConfiguration().getGradleVersion());
                        }
                        break;
                    case MAVEN:
                        if (escrowModuleConfig.getBuildConfiguration().getMavenVersion() != null) {
                            buildSystem.setBuildSystemVersion(escrowModuleConfig.getBuildConfiguration().getMavenVersion());
                        }
                        break;
                }
                build.setBuildSystem(buildSystem);
            }
            build.setDependencies(escrowModuleConfig.getBuildConfiguration().getDependencies());
            //TODO Set all attributes
            return build;
        });
    }

}
