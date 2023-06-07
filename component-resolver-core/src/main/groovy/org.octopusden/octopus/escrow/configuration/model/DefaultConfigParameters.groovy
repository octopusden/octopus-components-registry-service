package org.octopusden.octopus.escrow.configuration.model

import org.octopusden.octopus.escrow.BuildSystem
import org.octopusden.octopus.escrow.configuration.loader.VCSSettingsWrapper
import org.octopusden.octopus.escrow.model.BuildParameters
import org.octopusden.octopus.escrow.model.Distribution
import org.octopusden.octopus.releng.dto.JiraComponent
import groovy.transform.AutoClone
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.transform.TupleConstructor
import groovy.transform.TypeChecked

@TupleConstructor
@EqualsAndHashCode
@TypeChecked
@AutoClone
@ToString
class DefaultConfigParameters {

    BuildSystem buildSystem

    String buildFilePath

    BuildParameters buildParameters

    String artifactIdPattern

    String groupIdPattern;

    JiraComponent jiraComponent

    Boolean deprecated

    Distribution distribution

    VCSSettingsWrapper vcsSettingsWrapper

    String componentDisplayName

    String componentOwner

    String releaseManager

    String securityChampion

    String system

    String octopusVersion
}

