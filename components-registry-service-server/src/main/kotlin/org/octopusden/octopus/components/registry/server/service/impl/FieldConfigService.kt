package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.repository.RegistryConfigRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Risk-1 mitigation (master plan §7.0 Top risks #1) — server-side
 * enforcement of FieldConfig visibility. Pre-Risk-1, the Portal was
 * the only line of defence: if a buggy / outdated client sent a value
 * for a field whose admin-configured visibility is `"hidden"`, the
 * server would happily persist it.
 *
 * This service reads `registry_config[field-config].value` (a JSONB
 * blob written by `ConfigControllerV4.updateFieldConfig`) and exposes
 * a single visibility lookup. Callers — currently
 * `ComponentManagementServiceImpl.updateComponent` — gate scalar field writes on
 * `isHidden(...)` to silently strip values for hidden fields rather
 * than rejecting the request. The strip-vs-reject choice keeps the
 * server defensive without breaking existing clients that send
 * complete payloads.
 *
 * Path convention is section-prefixed per ADR-011:
 *   "component.displayName" / "build.javaVersion" / "jira.projectKey".
 *
 * Graceful fallbacks:
 *   - field-config row absent (fresh install)         → "editable"
 *   - JSONB shape mismatch                            → "editable"
 *   - section absent / field absent / visibility null → "editable"
 *
 * The "editable" default preserves pre-Risk-1 behaviour for any
 * un-configured field, so this service can land without a config-data
 * migration.
 */
@Service
@Transactional(readOnly = true)
class FieldConfigService(
    private val registryConfigRepository: RegistryConfigRepository,
) {
    fun visibilityFor(fieldPath: String): String {
        val parts = fieldPath.split('.', limit = 2)
        if (parts.size != 2) return EDITABLE

        val config = registryConfigRepository.findById(FIELD_CONFIG_KEY).orElse(null) ?: return EDITABLE

        @Suppress("UNCHECKED_CAST")
        val sectionMap =
            (config.value as? Map<String, Any?>)?.get(parts[0]) as? Map<String, Any?>
                ?: return EDITABLE

        @Suppress("UNCHECKED_CAST")
        val entry = sectionMap[parts[1]] as? Map<String, Any?> ?: return EDITABLE

        // Normalize: trim + lowercase before returning so a typo like
        // `"Hidden"` or `"hidden "` in admin-edited JSON still matches the
        // canonical "hidden"/"readonly"/"editable" tokens that callers
        // (and tests) compare against.
        return (entry["visibility"] as? String)
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: EDITABLE
    }

    fun isHidden(fieldPath: String): Boolean = visibilityFor(fieldPath) == HIDDEN

    companion object {
        private const val FIELD_CONFIG_KEY = "field-config"
        private const val EDITABLE = "editable"
        private const val HIDDEN = "hidden"
    }
}
