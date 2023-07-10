package org.octopusden.octopus.escrow

import groovy.transform.CompileStatic
import org.apache.commons.lang3.StringUtils
import org.apache.commons.lang3.Validate
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.octopusden.octopus.components.registry.api.model.Dependencies
import org.octopusden.octopus.escrow.model.Distribution
import org.octopusden.octopus.releng.dto.ComponentVersion
import org.octopusden.octopus.releng.dto.JiraComponent
import org.octopusden.releng.versions.ComponentVersionFormat
import org.octopusden.releng.versions.KotlinVersionFormatter
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.utils.StringUtilsKt

@CompileStatic
class ModelConfigPostProcessor {
    private static final Logger log = LogManager.getLogger(ModelConfigPostProcessor.class)

    private final ComponentVersion componentRelease
    private final VersionNames versionNames

    ModelConfigPostProcessor(ComponentVersion componentRelease, VersionNames versionNames) {
        Validate.notNull(componentRelease)
        Validate.notNull(versionNames)
        this.componentRelease = componentRelease
        this.versionNames = versionNames
    }

    String resolveVariables(String value) {
        if (StringUtils.isBlank(value)) {
            return value
        }

        def kvf = new KotlinVersionFormatter(versionNames)
        def factory = new NumericVersionFactory(versionNames)
        def version = factory.create(componentRelease.version)

        Map<String, String> context = new HashMap<>()
        for (def i : kvf.PREDEFINED_VARIABLES_LIST) {
            context.put(i.first, i.second.invoke(version))
        }
        for (def i : kvf.PREDEFINED_POSTPROCESSOR_LIST) {
            context.put(i.first, i.second.invoke(componentRelease.componentName, componentRelease.version))
        }
        return StringUtilsKt.expandContext(value, context)
    }

    Distribution resolveDistribution(final Distribution distribution) {
        if (distribution == null) {
            return null
        }
        return new Distribution(distribution.explicit(), distribution.external(), distribution.GAV(), distribution.securityGroups)
    }

    Dependencies resolveDependencies(final Dependencies dependencies) {
        dependencies != null ? new Dependencies(dependencies.autoUpdate) : null
    }

    JiraComponent resolveJiraConfiguration(final JiraComponent jiraComponent) {
        if (jiraComponent == null) {
            return null
        }
        def componentVersionFormat = jiraComponent.componentVersionFormat
        final ComponentVersionFormat enrichedComponentVersionFormat = componentVersionFormat == null ? null : ComponentVersionFormat.create(
                componentVersionFormat.majorVersionFormat,
                componentVersionFormat.releaseVersionFormat,
                componentVersionFormat.buildVersionFormat != null ? componentVersionFormat.buildVersionFormat : componentVersionFormat.releaseVersionFormat,
                componentVersionFormat.lineVersionFormat != null ? componentVersionFormat.lineVersionFormat : componentVersionFormat.majorVersionFormat
        )
        return new JiraComponent(jiraComponent.projectKey, jiraComponent.displayName, enrichedComponentVersionFormat, jiraComponent.componentInfo, jiraComponent.technical)
    }

    org.octopusden.octopus.escrow.model.VCSSettings resolveVariables(org.octopusden.octopus.escrow.model.VCSSettings vcsSettings) {
        return org.octopusden.octopus.escrow.model.VCSSettings.create(vcsSettings.externalRegistry, vcsSettings.versionControlSystemRoots.collect {
            resolveVariables(it)
        })
    }

    org.octopusden.octopus.escrow.model.VersionControlSystemRoot resolveVariables(org.octopusden.octopus.escrow.model.VersionControlSystemRoot versionControlSystemRoot) {
        Validate.notNull(versionControlSystemRoot, "versionControlSystemRoot can't be null")
        String tag = resolveVariables(versionControlSystemRoot.tag)
        String vcsPath = resolveVariables(versionControlSystemRoot.vcsPath)
        String branch = resolveVariables(versionControlSystemRoot.branch)
        org.octopusden.octopus.escrow.model.VersionControlSystemRoot.create(versionControlSystemRoot.name, versionControlSystemRoot.repositoryType, vcsPath, tag, branch)
    }

    ReleaseInfo resolveVariables(ReleaseInfo releaseInfo) {
        if (releaseInfo == null) {
            return null
        }
        def vcsSettings = resolveVariables(releaseInfo.vcsSettings)
        String buildFilePath = resolveVariables(releaseInfo.buildFilePath)
        org.octopusden.octopus.escrow.model.BuildParameters buildParameters = resolveVariables(releaseInfo.buildParameters)

        return ReleaseInfo.create(vcsSettings, releaseInfo.buildSystem,
                buildFilePath, buildParameters, releaseInfo.distribution, releaseInfo.deprecated(), releaseInfo.getEscrow().orElse(null))
    }

    org.octopusden.octopus.escrow.model.BuildParameters resolveVariables(org.octopusden.octopus.escrow.model.BuildParameters buildParameters) {
        if (buildParameters == null) {
            return null
        }
        return org.octopusden.octopus.escrow.model.BuildParameters.create(buildParameters.javaVersion, buildParameters.mavenVersion, buildParameters.gradleVersion, buildParameters.requiredProject,
                buildParameters.projectVersion, resolveVariables(buildParameters.systemProperties), buildParameters.getBuildTasks(), buildParameters.getTools(), buildParameters.getBuildTools())
    }
}
