package org.octopusden.octopus.components.registry.server.dto.v4

data class HistoryImportResult(
    val targetRef: String,
    val targetSha: String,
    val processedCommits: Int,
    val skippedNoGroovy: Int,
    val skippedParseError: Int,
    val skippedUnknownNames: Int,
    val auditRecords: Int,
    val durationMs: Long,
)
