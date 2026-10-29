package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.mapper.ALL_VERSIONS
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig

/**
 * Fail-loud guard for per-component-only `distribution.*` fields (CRS #387).
 *
 * `distribution.explicit`, `distribution.external` and
 * `distribution.securityGroups.read` are per-component by design (ADR-018): the
 * import stores only the base value ([ImportServiceImpl.buildComponentEntity]),
 * and per-range declarations that differ were **silently dropped** — a data-loss
 * trap discovered via the `ee-component-with-version-ranges` DMS FT fixture.
 * The other `distribution.*` sub-fields (maven / docker / packages / fileUrl)
 * remain per-range via the marker child-collection route and are untouched here.
 *
 * Detection: these three fields must **resolve to the same value in every
 * declared config**. The DSL loader resolves an omitted per-range field to the
 * value it inherits from the component default (EscrowConfigurationLoader
 * `parseDistributionSection`, `containsKey(...) ? declared : default`), so if no
 * range declares one of them every config resolves identically and the guard is
 * silent. A resolved difference between configs can only come from an explicit
 * per-range declaration — exactly the data-loss case the import silently dropped.
 *
 * A uniformity check (rather than compare-each-against-an-arbitrary-base) is used
 * deliberately: it is anchor-independent (correct whether or not an ALL_VERSIONS
 * block exists), reports every offending field in one pass, and names the actual
 * diverging ranges instead of implying the omitting one "declared" the value.
 */
internal fun validatePerComponentDistributionInvariants(
    componentKey: String,
    configs: List<EscrowModuleConfig>,
) {
    if (configs.size < 2) return

    val fields: List<Pair<String, (EscrowModuleConfig) -> Any?>> = listOf(
        "distribution.explicit" to { c -> c.distribution?.explicit() ?: false },
        "distribution.external" to { c -> c.distribution?.external() ?: false },
        "distribution.securityGroups.read" to { c ->
            c.distribution
                ?.securityGroups
                ?.read
                ?.takeIf { it.isNotBlank() }
        },
    )

    val violations = fields.mapNotNull { (attribute, extractor) ->
        val byValue = configs.groupBy(extractor)
        if (byValue.size <= 1) return@mapNotNull null
        val examples = byValue.entries.joinToString(", ") { (value, cfgs) ->
            "'${cfgs.first().versionRangeString ?: ALL_VERSIONS}'=$value"
        }
        "$attribute ($examples)"
    }

    if (violations.isEmpty()) return

    throw IllegalStateException(
        "Component '$componentKey' varies per-component-only distribution field(s) across version " +
            "ranges: ${violations.joinToString("; ")}. These fields (explicit / external / " +
            "securityGroups.read) are per-component only — declare each once at the top-level " +
            "distribution block so every range resolves the same value. The other distribution.* " +
            "fields (maven/docker/packages/fileUrl) remain per-range. See CRS #387 / ADR-018.",
    )
}
