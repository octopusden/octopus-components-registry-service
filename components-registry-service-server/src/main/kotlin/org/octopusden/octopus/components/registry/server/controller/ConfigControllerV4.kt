package org.octopusden.octopus.components.registry.server.controller

import org.octopusden.octopus.components.registry.server.entity.RegistryConfigEntity
import org.octopusden.octopus.components.registry.server.repository.RegistryConfigRepository
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * `registry_config.value` is a `Map<String, Any?>` column serialized to TEXT
 * via `@JdbcTypeCode(SqlTypes.JSON)`. Hibernate handles JSON marshalling on
 * read and write — the controller just passes the map through.
 */
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
        @RequestBody value: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> = writeConfig("field-config", value)

    @PutMapping("/admin/config/component-defaults")
    @PreAuthorize("@permissionEvaluator.canImport()")
    fun updateComponentDefaults(
        @RequestBody value: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> = writeConfig("component-defaults", value)

    private fun readConfig(key: String): ResponseEntity<Map<String, Any?>> =
        registryConfigRepository.findById(key)
            .map { ResponseEntity.ok(it.value) }
            .orElse(ResponseEntity.ok(emptyMap()))

    private fun writeConfig(
        key: String,
        value: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        val entity =
            registryConfigRepository.findById(key).orElse(RegistryConfigEntity(key = key))
        entity.value = value
        entity.updatedAt = Instant.now()
        registryConfigRepository.save(entity)
        return ResponseEntity.ok(value)
    }
}
