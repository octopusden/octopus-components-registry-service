package org.octopusden.octopus.escrow.configuration.model

import groovy.transform.TypeChecked

@TypeChecked
class EscrowModule {

    String moduleName

    List<EscrowModuleConfig> moduleConfigurations = new ArrayList<>()

    @Override
    String toString() {
        return "EscrowModule{" +
                "componentName='" + moduleName + '\'' +
                ", moduleConfigurations=" + moduleConfigurations +
                '}'
    }
}
