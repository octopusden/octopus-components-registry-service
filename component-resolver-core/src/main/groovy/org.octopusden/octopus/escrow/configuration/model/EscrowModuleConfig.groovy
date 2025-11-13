package org.octopusden.octopus.escrow.configuration.model

import groovy.transform.AutoClone
import groovy.transform.AutoCloneStyle
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.transform.TupleConstructor
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

import static org.octopusden.octopus.escrow.configuration.validation.EscrowConfigValidator.SPLIT_PATTERN

@TupleConstructor
@AutoClone(style = AutoCloneStyle.CLONE)
@EqualsAndHashCode(includeFields = true, includes = ["buildSystem", "artifactIdPattern", "groupIdPattern",// "versionRange",
        "buildFilePath", "jiraConfiguration", "buildConfiguration", "deprecated", "vcsSettings",
        "distribution", "componentDisplayName", "componentOwner", "releaseManager", "securityChampion", "system",
        "clientCode", "releasesInDefaultBranch", "solution", "parentComponent", "octopusVersion", "escrow", "productType",
        "copyright"])
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

    private String clientCode

    private Boolean releasesInDefaultBranch

    private Boolean solution

    private String parentComponent

    private String octopusVersion

    private String copyright

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

    Set<String> getSystemSet() {
        Set<String> systemSet = new HashSet<>()
        if (system != null) {
            for(String s : system.split(SPLIT_PATTERN)) {
                if (!s.isEmpty()) {
                    systemSet.add(s)
                }
            }
        }
        return systemSet
    }

    String getClientCode() {
        return clientCode
    }

    Boolean getReleasesInDefaultBranch() {
        return releasesInDefaultBranch
    }

    void setReleasesInDefaultBranch(Boolean releasesInDefaultBranch) {
        this.releasesInDefaultBranch = releasesInDefaultBranch
    }

    Boolean getSolution() {
        return solution
    }

    void setSolution(Boolean solution) {
        this.solution = solution
    }

    String getParentComponent() {
        return parentComponent
    }

    String getComponentDisplayName() {
        return componentDisplayName
    }

    String getOctopusVersion() {
        return octopusVersion
    }

    String getCopyright() {
        return copyright
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
