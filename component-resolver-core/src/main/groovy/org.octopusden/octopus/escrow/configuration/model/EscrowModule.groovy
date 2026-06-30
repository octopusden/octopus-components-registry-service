package org.octopusden.octopus.escrow.configuration.model

import groovy.transform.TypeChecked

@TypeChecked
class EscrowModule {

    String moduleName

    List<EscrowModuleConfig> moduleConfigurations = new ArrayList<>()

    /**
     * Component-level scalar representative (ADR-018 decoupled model): the config whose component-level
     * scalars (escrow, owner, RM/SC, distribution, ...) the controllers expose on the non-versioned wire
     * DTO. The DB resolver sets this to the resolved BASE / all-versions config so the representative is
     * decoupled from how the version ranges are enumerated — `moduleConfigurations[0]` is NO LONGER the
     * implicit source of that truth. Left null by the legacy in-memory loader, whose `moduleConfigurations`
     * is already in DSL-declaration order; controllers fall back to `moduleConfigurations[0]` then.
     */
    EscrowModuleConfig componentLevelConfiguration = null

    @Override
    String toString() {
        return "EscrowModule{" +
                "componentName='" + moduleName + '\'' +
                ", moduleConfigurations=" + moduleConfigurations +
                ", componentLevelConfiguration=" + componentLevelConfiguration +
                '}'
    }
}
