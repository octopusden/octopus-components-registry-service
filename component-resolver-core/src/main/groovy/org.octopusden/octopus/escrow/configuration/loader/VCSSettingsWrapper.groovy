package org.octopusden.octopus.escrow.configuration.loader

import org.octopusden.octopus.escrow.model.VCSSettings
import org.octopusden.octopus.escrow.model.VersionControlSystemRoot
import groovy.transform.TupleConstructor

@TupleConstructor
class VCSSettingsWrapper {

    VCSSettings vcsSettings

    VersionControlSystemRoot defaultVCSSettings

    Map<String, List<String>> vscRootName2ParametersFromDefaultsMap = [:]

}
