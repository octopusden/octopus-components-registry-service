package org.octopusden.octopus.escrow.configuration.model

import org.octopusden.octopus.components.registry.api.VersionedComponentConfiguration
import org.octopusden.octopus.components.registry.api.beans.VersionedComponentConfigurationBean
import org.octopusden.octopus.components.registry.api.enums.ProductTypes
import org.octopusden.octopus.components.registry.api.escrow.Escrow
import org.octopusden.octopus.crs.resolver.core.converter.BuildParametersConverter
import org.octopusden.octopus.crs.resolver.core.converter.VCSSettingsConverter
import org.octopusden.octopus.escrow.BuildSystem
import org.octopusden.octopus.escrow.model.BuildParameters
import org.octopusden.octopus.escrow.model.Distribution
import org.octopusden.octopus.escrow.model.VCSSettings
import org.octopusden.octopus.releng.dto.JiraComponent
import groovy.transform.AutoClone
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.transform.TupleConstructor
import groovy.transform.AutoCloneStyle

@TupleConstructor
@AutoClone(style = AutoCloneStyle.CLONE)
@EqualsAndHashCode(includeFields = true, includes = ["buildSystem", "artifactIdPattern", "groupIdPattern",// "versionRange",
    "buildFilePath", "jiraConfiguration", "buildConfiguration", "deprecated", "vcsSettings",
    "distribution", "componentDisplayName", "componentOwner", "releaseManager", "securityChampion", "system", "octopusVersion", "escrow", "productType"])
@ToString(includeFields = true)
class EscrowModuleConfig {
    private BuildSystem buildSystem

    private String artifactIdPattern

    private String groupIdPattern

    private String versionRange

    private String buildFilePath

    private JiraComponent jiraConfiguration

    private BuildParameters buildConfiguration

    private boolean deprecated

    private VCSSettings vcsSettings

    private Distribution distribution

    private String componentDisplayName

    private String componentOwner

    private String releaseManager

    private String securityChampion

    private String system

    private String octopusVersion

    Escrow escrow

    ProductTypes productType

    BuildSystem getBuildSystem() {
        return buildSystem
    }

    String getArtifactIdPattern() {
        return artifactIdPattern
    }

    String getGroupIdPattern() {
        return groupIdPattern
    }

    String getVersionRangeString() {
        return versionRange
    }

    String getBuildFilePath() {
        return buildFilePath
    }

    JiraComponent getJiraConfiguration() {
        return jiraConfiguration
    }

    BuildParameters getBuildConfiguration() {
        return buildConfiguration
    }

    boolean isDeprecated() {
        return deprecated
    }

    VCSSettings getVcsSettings() {
        return vcsSettings
    }

    Distribution getDistribution() {
        return distribution
    }

    String getComponentOwner() {
        return componentOwner
    }

    String getReleaseManager() {
        return releaseManager
    }

    String getSecurityChampion() {
        return securityChampion
    }

    String getSystem() {
        return system
    }

    String getComponentDisplayName() {
        return componentDisplayName
    }

    String getOctopusVersion() {
        return octopusVersion
    }

    VersionedComponentConfiguration toVersionedComponent() {
        def component = new VersionedComponentConfigurationBean()
        if (buildConfiguration) {
            component.build = new BuildParametersConverter().convertFrom(this)
        }
        if (vcsSettings) {
            component.vcs = new VCSSettingsConverter().convertFrom(vcsSettings)
        }
        //TODO Set others attributes
        return component
    }
}
