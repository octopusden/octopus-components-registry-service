package org.octopusden.octopus.components.registry.server.service

interface ImportService {
    fun migrateComponent(
        name: String,
        dryRun: Boolean = false,
    ): MigrationResult

    fun migrateAllComponents(progress: MigrationProgressListener = MigrationProgressListener.NOOP): BatchMigrationResult

    fun getMigrationStatus(): MigrationStatus

    fun validateMigration(name: String): ValidationResult

    fun migrateDefaults(): Map<String, Any?>

    fun migrate(progress: MigrationProgressListener = MigrationProgressListener.NOOP): FullMigrationResult
}

data class MigrationResult(
    val componentName: String,
    val success: Boolean,
    val dryRun: Boolean,
    val message: String,
    val discrepancies: List<String> = emptyList(),
)

data class BatchMigrationResult(
    val total: Int,
    val migrated: Int,
    val failed: Int,
    val skipped: Int,
    val results: List<MigrationResult>,
)

data class MigrationStatus(
    val git: Long,
    val db: Long,
    val total: Long,
)

data class FullMigrationResult(
    val defaults: Map<String, Any?>,
    val components: BatchMigrationResult,
)

data class ValidationResult(
    val componentName: String,
    val valid: Boolean,
    val discrepancies: List<String>,
)
