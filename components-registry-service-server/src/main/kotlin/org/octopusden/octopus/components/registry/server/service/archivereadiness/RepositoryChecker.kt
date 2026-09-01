package org.octopusden.octopus.components.registry.server.service.archivereadiness

import org.octopusden.octopus.components.registry.server.dto.v4.Outcome
import org.octopusden.octopus.components.registry.server.util.VcsUrlCanonicalizer
import org.octopusden.octopus.vcsfacade.client.VcsFacadeClient
import org.octopusden.octopus.vcsfacade.client.common.exception.ArgumentsNotCompatibleException
import org.octopusden.octopus.vcsfacade.client.common.exception.NotFoundException
import org.springframework.stereotype.Service

@Service
class RepositoryChecker(
    private val vcsFacadeClient: VcsFacadeClient,
    private val sharingHelper: SharingHelper,
) : TargetChecker {

    override fun check(target: CheckTarget): CheckResult {
        val repo = try {
            vcsFacadeClient.getRepository(target.targetId)
        } catch (e: NotFoundException) {
            // Repository does not exist — a deleted repository is not live infrastructure.
            // Absence is a stronger end state than archived, so it passes.
            // sharedWith is always empty on an absence-PASSED entry (design decision 11).
            return CheckResult(Outcome.PASSED, reason = "Repository no longer exists")
        } catch (e: ArgumentsNotCompatibleException) {
            // No configured VCS provider serves this URL at all — a registry-data problem
            // (the recorded URL is unresolvable), not a VCS-system-outage. Naming the URL here
            // (rather than "VCS system unavailable") points whoever reads this at fixing the
            // component's data instead of waiting on a system that is, in fact, healthy
            // (design decision 14).
            return CheckResult(
                Outcome.UNKNOWN,
                reason = "Recorded repository URL is unresolvable by any configured VCS provider: ${target.targetId}",
            )
        } catch (e: Exception) {
            // Ambiguous failure — could be system unavailable, credential error, or
            // "no configured VCS provider for this URL". Fail closed.
            return CheckResult(Outcome.UNKNOWN, reason = "VCS system could not be consulted: ${e.message}")
        }
        return when (repo.archived) {
            true -> {
                val shared = sharingHelper.sharedWithForRepo(
                    VcsUrlCanonicalizer.canonicalize(target.targetId), target.componentId)
                CheckResult(Outcome.PASSED, sharedWith = shared)
            }
            false -> {
                val shared = sharingHelper.sharedWithForRepo(
                    VcsUrlCanonicalizer.canonicalize(target.targetId), target.componentId)
                if (shared.isNotEmpty()) CheckResult(Outcome.PASSED, sharedWith = shared)
                else CheckResult(Outcome.FAILED)
            }
            null -> CheckResult(Outcome.UNKNOWN, reason = "VCS system returned indeterminate archived state")
        }
    }
}
