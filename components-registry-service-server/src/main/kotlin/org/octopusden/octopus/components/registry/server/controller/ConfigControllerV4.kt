package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.octopusden.octopus.components.registry.server.entity.v2.RegistryConfigEntity
import org.octopusden.octopus.components.registry.server.repository.v2.RegistryConfigRepository
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * `registry_config.value` is TEXT-as-JSON under schema v2 (no JSONB). We
 * serialize to a JSON string at the boundary and let the @JdbcTypeCode mapping
 * on the entity persist it; on read we deserialize through Jackson so the
 * response shape stays a `Map<String, Any?>` for v4 clients.
 */
@RestController
@RequestMapping("rest/api/4")
class ConfigControllerV4(
    private val registryConfigRepository: RegistryConfigRepository,
    private val objectMapper: ObjectMapper,
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

    private fun readConfig(key: String): ResponseEntity<Map<String, Any?>> {
        val config = registryConfigRepository.findById(key)
        return if (config.isPresent) {
            val parsed = parseJsonMap(config.get().value)
            ResponseEntity.ok(parsed)
        } else {
            ResponseEntity.ok(emptyMap())
        }
    }

    private fun writeConfig(
        key: String,
        value: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        val entity =
            registryConfigRepository.findById(key).orElse(RegistryConfigEntity(key = key))
        entity.value = objectMapper.writeValueAsString(value)
        entity.updatedAt = Instant.now()
        registryConfigRepository.save(entity)
        return ResponseEntity.ok(value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseJsonMap(json: String): Map<String, Any?> =
        try {
            objectMapper.readValue(json, Map::class.java) as Map<String, Any?>
        } catch (_: Exception) {
            emptyMap()
        }
}
