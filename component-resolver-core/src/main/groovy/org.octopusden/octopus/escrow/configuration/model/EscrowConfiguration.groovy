package org.octopusden.octopus.escrow.configuration.model

import org.octopusden.releng.versions.VersionNames

class EscrowConfiguration {

    Map<String, EscrowModule> escrowModules = new HashMap<>()

    /**
     * Aggregator membership derived from DSL {@code components { ... }} blocks:
     * aggregator component key -> set of its sub-component keys. A TRUE aggregator
     * is a component that owns a {@code components{}} block; this map is the
     * authoritative source for ComponentGroup membership (NOT the flat
     * {@code parentComponent} field, which is a separate reference). Components not
     * present as a key here are not aggregators; sub-components appear only in the
     * value sets. Populated by the loader; empty when no aggregators are defined.
     */
    Map<String, Set<String>> aggregatorSubComponents = new HashMap<>()

    EscrowModule defaultConfiguration

    VersionNames versionNames

    @Override
    String toString() {
        return "EscrowConfiguration{" +
                "escrowModules=" + escrowModules +
                ", defaultConfiguration=" + defaultConfiguration +
                '}';
    }
}
