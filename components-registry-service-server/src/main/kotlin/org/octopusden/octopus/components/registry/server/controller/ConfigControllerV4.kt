package org.octopusden.octopus.components.registry.server.controller

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.repository.RegistryConfigRepository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Read-only view of the admin config blobs cached in `registry_config`.
 *
 * `field-config` and `component-defaults` are now **code-as-config** (managed in
 * `service-config`, bound via [AdminConfigProperties][org.octopusden.octopus.components.registry.server.config.AdminConfigProperties]
 * and synced into the `registry_config` cache by
 * [ConfigSyncService][org.octopusden.octopus.components.registry.server.service.impl.ConfigSyncService]).
 * The GET endpoints serve that cache unchanged (Portal + `FieldConfigService` read it);
 * the legacy PUT writers now return **410 Gone** — edits go through `service-config`
 * + `POST /rest/api/4/admin/reload-config`.
 *
 * `registry_config.value` is a `Map<String, Any?>` column serialized to TEXT via
 * `@JdbcTypeCode(SqlTypes.JSON)`; Hibernate handles JSON marshalling on read.
 */
@ConditionalOnDatabaseEnabled
@RestController
@RequestMapping("rest/api/4")
class ConfigControllerV4(
    private val registryConfigRepository: RegistryConfigRepository,
) {
    @GetMapping("/config/field-config")
    @PreAuthorize("@permissionEvaluator.hasPermission('ACCESS_COMPONENTS')")
    fun getFieldConfig(): ResponseEntity<Map<String, Any?>> = readConfig("field-config")

    @GetMapping("/config/component-defaults")
    @PreAuthorize("@permissionEvaluator.hasPermission('ACCESS_COMPONENTS')")
    fun getComponentDefaults(): ResponseEntity<Map<String, Any?>> = readConfig("component-defaults")

    @PutMapping("/admin/config/field-config")
    @PreAuthorize("@permissionEvaluator.canImport()")
    fun updateFieldConfig(
        @RequestBody @Suppress("UNUSED_PARAMETER") value: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> = gone("field-config")

    @PutMapping("/admin/config/component-defaults")
    @PreAuthorize("@permissionEvaluator.canImport()")
    fun updateComponentDefaults(
        @RequestBody @Suppress("UNUSED_PARAMETER") value: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> = gone("component-defaults")

    private fun readConfig(key: String): ResponseEntity<Map<String, Any?>> =
        registryConfigRepository.findById(key)
            .map { ResponseEntity.ok(it.value) }
            .orElse(ResponseEntity.ok(emptyMap()))

    /**
     * The blob is now managed as code; writes are no longer accepted. 410 Gone
     * (not 404/405) so an outdated client that still PUTs gets an explicit,
     * actionable signal rather than a generic mapping error.
     */
    private fun gone(key: String): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(HttpStatus.GONE).body(
            mapOf(
                "error" to "gone",
                "message" to (
                    "'$key' is managed as code in service-config and is read-only. " +
                        "Edit it in service-config and apply via POST /rest/api/4/admin/reload-config."
                ),
            ),
        )
}
