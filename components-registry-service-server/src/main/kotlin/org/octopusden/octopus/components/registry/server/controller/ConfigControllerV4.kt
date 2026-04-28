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

@RestController
@RequestMapping("rest/api/4")
class ConfigControllerV4(
    private val registryConfigRepository: RegistryConfigRepository,
) {
    @GetMapping("/config/field-config")
    @PreAuthorize("@permissionEvaluator.hasPermission('ACCESS_COMPONENTS')")
    fun getFieldConfig(): ResponseEntity<Map<String, Any?>> {
        val config = registryConfigRepository.findById("field-config")
        return if (config.isPresent) {
            ResponseEntity.ok(config.get().value)
        } else {
            ResponseEntity.ok(emptyMap())
        }
    }

    @GetMapping("/config/component-defaults")
    @PreAuthorize("@permissionEvaluator.hasPermission('ACCESS_COMPONENTS')")
    fun getComponentDefaults(): ResponseEntity<Map<String, Any?>> {
        val config = registryConfigRepository.findById("component-defaults")
        return if (config.isPresent) {
            ResponseEntity.ok(config.get().value)
        } else {
            ResponseEntity.ok(emptyMap())
        }
    }

    @PutMapping("/admin/config/field-config")
    @PreAuthorize("@permissionEvaluator.canImport()")
    fun updateFieldConfig(
        @RequestBody value: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        val entity =
            registryConfigRepository.findById("field-config").orElse(
                RegistryConfigEntity(key = "field-config"),
            )
        entity.value = value
        entity.updatedAt = Instant.now()
        registryConfigRepository.save(entity)
        return ResponseEntity.ok(value)
    }

    @PutMapping("/admin/config/component-defaults")
    @PreAuthorize("@permissionEvaluator.canImport()")
    fun updateComponentDefaults(
        @RequestBody value: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        val entity =
            registryConfigRepository.findById("component-defaults").orElse(
                RegistryConfigEntity(key = "component-defaults"),
            )
        entity.value = value
        entity.updatedAt = Instant.now()
        registryConfigRepository.save(entity)
        return ResponseEntity.ok(value)
    }
}
