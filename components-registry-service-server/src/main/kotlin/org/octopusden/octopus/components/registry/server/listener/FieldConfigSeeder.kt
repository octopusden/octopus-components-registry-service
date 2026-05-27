package org.octopusden.octopus.components.registry.server.listener

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

        val entity =
            registryConfigRepository.findById(FIELD_CONFIG_KEY)
                .orElse(RegistryConfigEntity(key = FIELD_CONFIG_KEY))
        // The Hibernate JSON converter exposes value as Map<String, Any?>.
        // We re-build the section / field maps explicitly so the seed never
        // mutates a structurally-incompatible existing blob (e.g. an admin
        // wrote a non-object under `component`).

        @Suppress("UNCHECKED_CAST")
        val root: MutableMap<String, Any?> =
            (entity.value as? Map<String, Any?>)?.toMutableMap() ?: mutableMapOf()

        @Suppress("UNCHECKED_CAST")
        val componentSection: MutableMap<String, Any?> =
            (root[SECTION_COMPONENT] as? Map<String, Any?>)?.toMutableMap() ?: mutableMapOf()

        @Suppress("UNCHECKED_CAST")
        val groupIdField: MutableMap<String, Any?> =
            (componentSection[FIELD_GROUP_ID] as? Map<String, Any?>)?.toMutableMap() ?: mutableMapOf()

        if (groupIdField[KEY_DEFAULT_VALUE] != null) {
            // Admin- or prior-seed-set value already present — never clobber.
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

    companion object {
        private const val FIELD_CONFIG_KEY = "field-config"
        private const val SECTION_COMPONENT = "component"
        private const val FIELD_GROUP_ID = "groupId"
        private const val KEY_DEFAULT_VALUE = "defaultValue"
    }
}
