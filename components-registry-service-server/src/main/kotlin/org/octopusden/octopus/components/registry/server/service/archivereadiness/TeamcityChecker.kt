package org.octopusden.octopus.components.registry.server.service.archivereadiness

import org.octopusden.octopus.components.registry.server.dto.v4.Outcome
import org.springframework.stereotype.Service

@Service
class TeamcityChecker(
    private val tcDescendantLookup: TcDescendantLookup,
    private val sharingHelper: SharingHelper,
) : TargetChecker {

    override fun check(target: CheckTarget): CheckResult {
        return when (val result = tcDescendantLookup.findDescendantsAndSelf(target.targetId)) {
            is TcDescendantResult.SystemUnavailable ->
                CheckResult(Outcome.UNKNOWN, reason = "TeamCity system could not be consulted")
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
}
