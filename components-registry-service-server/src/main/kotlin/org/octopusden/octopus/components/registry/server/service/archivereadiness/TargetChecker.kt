package org.octopusden.octopus.components.registry.server.service.archivereadiness

import org.octopusden.octopus.components.registry.server.dto.v4.JiraIssueRef
import org.octopusden.octopus.components.registry.server.dto.v4.Outcome
import org.octopusden.octopus.components.registry.server.dto.v4.ReasonKind
import java.util.UUID

interface TargetChecker {
    fun check(target: CheckTarget): CheckResult
}

data class CheckTarget(
    val targetId: String,
    val componentId: UUID,
    val componentKey: String,
)

data class CheckResult(
    val outcome: Outcome,
    val reason: String? = null,
    val reasonKind: ReasonKind? = null,
    val sharedWith: List<String> = emptyList(),
    val openIssues: List<JiraIssueRef> = emptyList(),
)
