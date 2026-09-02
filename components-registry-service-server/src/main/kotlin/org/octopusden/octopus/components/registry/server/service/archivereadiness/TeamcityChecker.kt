package org.octopusden.octopus.components.registry.server.service.archivereadiness

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.dto.v4.Outcome
import org.octopusden.octopus.components.registry.server.dto.v4.ReasonKind
import org.springframework.stereotype.Service

// SYS-047: depends (via SharingHelper) on beans that inject JPA repositories, so it must be
// dropped in no-db mode too — see ConditionalOnDatabaseEnabled's kdoc ("or another bean so
// annotated").
@ConditionalOnDatabaseEnabled
@Service
class TeamcityChecker(
    private val tcDescendantLookup: TcDescendantLookup,
    private val sharingHelper: SharingHelper,
) : TargetChecker {
    override fun check(target: CheckTarget): CheckResult =
        when (val result = tcDescendantLookup.findDescendantsAndSelf(target.targetId)) {
            is TcDescendantResult.SystemUnavailable ->
                CheckResult(
                    Outcome.UNKNOWN,
                    reason = "TeamCity system could not be consulted",
                    reasonKind = ReasonKind.SYSTEM_UNAVAILABLE,
                )
            is TcDescendantResult.ProjectAbsent ->
                CheckResult(Outcome.PASSED, reason = "TeamCity project no longer exists")
            is TcDescendantResult.Found -> {
                val descendants = result.projectIds - target.targetId
                val shared = sharingHelper.sharedWithForTcProject(target.targetId, descendants, target.componentId)
                val selfArchived = target.targetId in result.archivedIds
                when {
                    shared.isNotEmpty() -> CheckResult(Outcome.PASSED, sharedWith = shared)
                    selfArchived -> CheckResult(Outcome.PASSED)
                    else -> CheckResult(Outcome.FAILED)
                }
            }
        }
}
