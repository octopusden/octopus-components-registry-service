package org.octopusden.octopus.components.registry.server.controller

import org.octopusden.octopus.components.registry.server.dto.v4.HistoryImportResult
import org.octopusden.octopus.components.registry.server.service.BatchMigrationResult
import org.octopusden.octopus.components.registry.server.service.FullMigrationResult
import org.octopusden.octopus.components.registry.server.service.GitHistoryImportService
import org.octopusden.octopus.components.registry.server.service.ImportService
import org.octopusden.octopus.components.registry.server.service.MigrationResult
import org.octopusden.octopus.components.registry.server.service.MigrationStatus
import org.octopusden.octopus.components.registry.server.service.ValidationResult
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("rest/api/4/admin")
class AdminControllerV4(
    private val importService: ImportService,
    private val gitHistoryImportService: GitHistoryImportService,
) {
    @PostMapping("/migrate-component/{name}")
    fun migrateComponent(
        @PathVariable name: String,
        @RequestParam(defaultValue = "false") dryRun: Boolean,
    ): ResponseEntity<MigrationResult> = ResponseEntity.ok(importService.migrateComponent(name, dryRun))

    @PostMapping("/migrate-components")
    fun migrateAllComponents(): ResponseEntity<BatchMigrationResult> = ResponseEntity.ok(importService.migrateAllComponents())

    @GetMapping("/migration-status")
    fun getMigrationStatus(): ResponseEntity<MigrationStatus> = ResponseEntity.ok(importService.getMigrationStatus())

    @PostMapping("/validate-migration/{name}")
    fun validateMigration(
        @PathVariable name: String,
    ): ResponseEntity<ValidationResult> = ResponseEntity.ok(importService.validateMigration(name))

    @PostMapping("/import")
    fun importComponents(): ResponseEntity<BatchMigrationResult> = ResponseEntity.ok(importService.migrateAllComponents())

    @PostMapping("/migrate")
    fun migrate(): ResponseEntity<FullMigrationResult> = ResponseEntity.ok(importService.migrate())

    @PostMapping("/migrate-defaults")
    fun migrateDefaults(): ResponseEntity<Map<String, Any?>> = ResponseEntity.ok(importService.migrateDefaults())

    @GetMapping("/export")
    fun exportComponents(): ResponseEntity<Map<String, String>> = ResponseEntity.ok(mapOf("status" to "not_implemented"))

    @PostMapping("/migrate-history")
    fun migrateHistory(
        @RequestParam(required = false) toRef: String?,
        @RequestParam(defaultValue = "false") reset: Boolean,
    ): ResponseEntity<HistoryImportResult> = ResponseEntity.ok(gitHistoryImportService.importHistory(toRef, reset))
}
