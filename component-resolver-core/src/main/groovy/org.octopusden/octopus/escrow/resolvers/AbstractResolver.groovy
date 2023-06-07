package org.octopusden.octopus.escrow.resolvers

import org.octopusden.octopus.escrow.ModelConfigPostProcessor
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig
import org.octopusden.octopus.escrow.model.VCSSettings
import org.octopusden.octopus.escrow.model.VersionControlSystemRoot
import org.octopusden.octopus.releng.dto.ComponentVersion
import org.octopusden.releng.versions.KotlinVersionFormatter
import org.octopusden.releng.versions.NumericVersion
import org.octopusden.releng.versions.VersionFormatter
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

import java.util.concurrent.TimeUnit

class AbstractResolver {
    private static final Logger LOG = LogManager.getLogger(AbstractResolver.class)
    protected EscrowConfigurationLoader escrowConfigurationLoader

    AbstractResolver(EscrowConfigurationLoader escrowConfigurationLoader) {
        this.escrowConfigurationLoader = escrowConfigurationLoader
    }

    EscrowModuleConfig getEscrowModuleConfig(ComponentVersion componentRelease) {
        return getEscrowModuleConfig(componentRelease, false, [:])
    }

    EscrowModuleConfig getEscrowModuleConfig(ComponentVersion componentRelease, boolean ignoreUnknownFields, Map params) {
        final long tStart = System.nanoTime()
        def configuration = ignoreUnknownFields ?  escrowConfigurationLoader.loadModuleConfigurationIgnoreValidationForUnknownFields(componentRelease, params) : escrowConfigurationLoader.loadModuleConfiguration(componentRelease, params)
        LOG.info("Resolving $componentRelease took {}ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - tStart))
        configuration
    }

    static VCSSettings createVCSRootWithFormattedBranch(VCSSettings vcsSettings, String componentName, String version) {
        VersionFormatter versionFormatter = new KotlinVersionFormatter();
        def parsedVersion = NumericVersion.parse(version)
        Collection<VersionControlSystemRoot> vcsRootsResolved = new ArrayList<>()
        vcsSettings.getVersionControlSystemRoots().each { VersionControlSystemRoot root ->
            String formattedBranch = versionFormatter.format(root.getBranch(), parsedVersion)
            vcsRootsResolved.add(VersionControlSystemRoot.create(root.name, root.repositoryType, root.vcsPath, root.tag, formattedBranch == "null" ? null : formattedBranch))
        }
        VCSSettings vcsSettingsNew = VCSSettings.create(vcsSettings.externalRegistry, vcsRootsResolved)
        ModelConfigPostProcessor modelConfigPostProcessor = new ModelConfigPostProcessor(ComponentVersion.create(componentName, version))
        return modelConfigPostProcessor.resolveVariables(vcsSettingsNew)
    }
}
