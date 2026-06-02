package org.octopusden.octopus.components.registry.server.listener

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.entity.RegistryConfigEntity
import org.octopusden.octopus.components.registry.server.repository.RegistryConfigRepository
import org.octopusden.octopus.escrow.config.ConfigHelper
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Seeds the `field-config` registry_config blob with a default value for
 * `component.groupId.defaultValue` on startup, derived from
 * `ConfigHelper.supportedGroupIds().first()`.
 *
 * Why a seeder rather than a Flyway migration:
 *  - The default groupId prefix is environment-specific (read from
 *    `components-registry.supportedGroupIds` in `application*.yml`), so it
 *    cannot be baked into a static SQL migration without losing
 *    portability across deploys.
 *  - The Portal's Create Component dialog reads
 *    `useFieldConfigEntry('component.groupId').entry.defaultValue` and uses
 *    it as the auto-suggest parent prefix. On a fresh install with an
 *    empty field-config blob, that value would be undefined and the
 *    auto-suggest would yield nothing until an admin manually edited the
 *    field-config.
 *
 * Idempotency:
 *  - Runs on every `ApplicationReadyEvent`.
 *  - If `component.groupId.defaultValue` is already set in the
 *    field-config blob (admin-edited via `PUT /admin/config/field-config`,
 *    or a prior seeder run), the existing value is preserved — admins
 *    must be able to override the seed without it being clobbered on
 *    every restart.
 *  - If `supportedGroupIds()` is empty (mis-configured env), the seeder
 *    logs a warning and leaves the field-config untouched. The Portal's
 *    Create dialog already handles "no default available" by leaving the
 *    auto-suggest dormant.
 *
 * The blob shape matches the convention used by `FieldConfigService`:
 * `{"<section>": {"<field>": {"defaultValue": ...}}}` — i.e.
 * `{"component": {"groupId": {"defaultValue": "<prefix>"}}}`.
 */
@ConditionalOnDatabaseEnabled
@Component
class FieldConfigSeeder(
    private val registryConfigRepository: RegistryConfigRepository,
    private val environment: Environment,
) {
    private val log = LoggerFactory.getLogger(FieldConfigSeeder::class.java)

    private val configHelper: ConfigHelper by lazy { ConfigHelper(environment) }

    @EventListener(ApplicationReadyEvent::class)
    @Transactional
    fun seedComponentGroupIdDefault() {
        val supported = runCatching { configHelper.supportedGroupIds().toList() }.getOrElse {
            log.warn(
                "FieldConfigSeeder: supportedGroupIds() unavailable ({}); skipping component.groupId.defaultValue seed.",
                it.message,
            )
            return
        }
        val firstPrefix =
            supported.firstOrNull { it.isNotBlank() }
                ?: run {
                    log.warn(
                        "FieldConfigSeeder: supportedGroupIds() is empty; cannot seed " +
                            "component.groupId.defaultValue. Configure components-registry.supportedGroupIds " +
                            "in application.yml or seed via PUT /admin/config/field-config.",
                    )
                    return
                }

        val existing = registryConfigRepository.findById(FIELD_CONFIG_KEY).orElse(null)
        val entity = existing ?: RegistryConfigEntity(key = FIELD_CONFIG_KEY)

        // The Hibernate JSON converter exposes value as Map<String, Any?>.
        // We treat the blob as a tree of nested maps, but admin tooling
        // (PUT /admin/config/field-config) accepts ANY JSON shape — so the
        // root, the `component` section, or the `groupId` field could each
        // independently be a non-Map (list, scalar, null). The seeder must
        // NOT silently overwrite such admin data: it bails with a warning
        // and leaves the blob untouched, so the next restart re-attempts
        // once the operator has reshaped the JSON manually. The Portal's
        // Create dialog already handles "no default available" gracefully.
        if (existing != null) {
            val incompatible = describeIncompatibleShape(existing.value)
            if (incompatible != null) {
                log.warn(
                    "FieldConfigSeeder: existing 'field-config' blob has an incompatible shape ({}); " +
                        "skipping seed to avoid clobbering admin data. Reshape via " +
                        "PUT /admin/config/field-config and restart, or accept the absence of " +
                        "the auto-suggest default in the Portal Create Component dialog.",
                    incompatible,
                )
                return
            }
        }

        @Suppress("UNCHECKED_CAST")
        val root: MutableMap<String, Any?> = (entity.value as Map<String, Any?>).toMutableMap()

        @Suppress("UNCHECKED_CAST")
        val componentSection: MutableMap<String, Any?> =
            (root[SECTION_COMPONENT] as? Map<String, Any?>)?.toMutableMap() ?: mutableMapOf()

        @Suppress("UNCHECKED_CAST")
        val groupIdField: MutableMap<String, Any?> =
            (componentSection[FIELD_GROUP_ID] as? Map<String, Any?>)?.toMutableMap() ?: mutableMapOf()

        if (groupIdField.containsKey(KEY_DEFAULT_VALUE)) {
            // Admin- or prior-seed-set value already present — never clobber.
            // Use key-presence (not `!= null`) so an explicit `defaultValue: null`
            // is honoured as an intentional opt-out from the auto-suggest
            // default. Previously the seeder treated `null` as "missing" and
            // silently re-seeded it on every restart, which made the
            // opt-out impossible.
            log.debug(
                "FieldConfigSeeder: component.groupId.defaultValue already set to '{}'; preserved.",
                groupIdField[KEY_DEFAULT_VALUE],
            )
            return
        }

        groupIdField[KEY_DEFAULT_VALUE] = firstPrefix
        componentSection[FIELD_GROUP_ID] = groupIdField
        root[SECTION_COMPONENT] = componentSection

        entity.value = root
        entity.updatedAt = Instant.now()
        registryConfigRepository.save(entity)
        log.info(
            "FieldConfigSeeder: seeded component.groupId.defaultValue='{}' (first of {} supportedGroupIds).",
            firstPrefix,
            supported.size,
        )
    }

    /**
     * Walk the existing `field-config` blob and return a human-readable
     * description of the first incompatible shape — or `null` if the blob is
     * structurally compatible with the seeder's nested-map convention.
     *
     * Checks (in order):
     *  - root must be a `Map<*, *>`. In practice this branch is mostly
     *    defensive — `PUT /admin/config/field-config` is typed
     *    `@RequestBody Map<String, Any?>`, so Jackson rejects a non-object
     *    JSON before it ever reaches the repository, and Hibernate's
     *    `@JdbcTypeCode(SqlTypes.JSON)` would also fail to deserialise a
     *    non-object back into the entity's `Map` field. The guard
     *    therefore protects against direct-SQL writes only; if a future
     *    code path swaps to a different deserialisation strategy that
     *    tolerates non-objects, this branch becomes load-bearing.
     *  - the `component` key (if present) must be a Map.
     *  - the `component.groupId` key (if present) must be a Map.
     *
     * Returning `null` means "safe to merge" — the seeder may proceed.
     * Returning a non-null string is the message logged at WARN level
     * before the seeder bails out. Visible-for-test (`internal`) so the
     * test suite can pin the "incompatible blob is preserved" contract
     * directly without going through the full @EventListener lifecycle.
     */
    internal fun describeIncompatibleShape(value: Any?): String? {
        if (value !is Map<*, *>) {
            return "root is ${value?.javaClass?.simpleName ?: "null"}, expected a JSON object"
        }
        val componentSection = value[SECTION_COMPONENT]
        if (componentSection != null && componentSection !is Map<*, *>) {
            return "'$SECTION_COMPONENT' is ${componentSection.javaClass.simpleName}, expected a JSON object"
        }
        val groupIdField = (componentSection as? Map<*, *>)?.get(FIELD_GROUP_ID)
        if (groupIdField != null && groupIdField !is Map<*, *>) {
            return "'$SECTION_COMPONENT.$FIELD_GROUP_ID' is ${groupIdField.javaClass.simpleName}, expected a JSON object"
        }
        return null
    }

    companion object {
        private const val FIELD_CONFIG_KEY = "field-config"
        private const val SECTION_COMPONENT = "component"
        private const val FIELD_GROUP_ID = "groupId"
        private const val KEY_DEFAULT_VALUE = "defaultValue"
    }
}
