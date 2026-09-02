package org.octopusden.octopus.components.registry.server.service.archivereadiness

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.dto.v4.Outcome
import org.octopusden.octopus.components.registry.server.dto.v4.ReasonKind
import org.octopusden.octopus.components.registry.server.util.VcsUrlCanonicalizer
import org.octopusden.octopus.vcsfacade.client.VcsFacadeClient
import org.octopusden.octopus.vcsfacade.client.common.exception.NotFoundException
import org.springframework.stereotype.Service

// SYS-047: depends (via SharingHelper) on beans that inject JPA repositories, so it must be
// dropped in no-db mode too — see ConditionalOnDatabaseEnabled's kdoc ("or another bean so
// annotated").
@ConditionalOnDatabaseEnabled
@Service
class RepositoryChecker(
    private val vcsFacadeClient: VcsFacadeClient,
    private val sharingHelper: SharingHelper,
) : TargetChecker {
    // NotFoundException here is expected, successful information (the repository was
    // confirmed absent), not a discarded error, so it is intentionally not logged/rethrown.
    // The plain Exception catch below is likewise deliberate: this check must fail closed
    // to UNKNOWN on ANY failure from the VCS client, not just specific exception types — an
    // unanticipated exception type from a third-party client is itself evidence the system
    // couldn't be consulted reliably.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun check(target: CheckTarget): CheckResult {
        val repo = try {
            vcsFacadeClient.getRepository(target.targetId)
        } catch (e: NotFoundException) {
            // Repository does not exist — a deleted repository is not live infrastructure.
            // Absence is a stronger end state than archived, so it passes.
            // sharedWith is always empty on an absence-PASSED entry (design decision 11).
            return CheckResult(Outcome.PASSED, reason = "Repository no longer exists")
        } catch (e: Exception) {
            // "No configured VCS provider for this URL" is a registry-data problem (the recorded
            // URL is unresolvable), not a VCS-system-outage — but the vcs-facade client (pinned
            // at 3.0.36) can't distinguish it by exception TYPE: server-side it's a plain
            // IllegalStateException with no dedicated @ExceptionHandler, so it falls through to
            // the catch-all handler and comes back over the wire as a bare VcsFacadeException,
            // indistinguishable by type from any other generic server-side failure (see
            // VcsManagerImpl.getVcsServiceForSshUrl, ExceptionInfoHandler, VcsFacadeErrorCode.OTHER
            // in octopus-vcs-facade). The message text is a fixed template controlled by the
            // vcs-facade server codebase itself (not user input), so matching on it here is a
            // bounded, acceptable heuristic — not the fragile display-name fuzzy-matching this
            // project avoids elsewhere. A non-matching message falls through to the generic
            // reason below, which was already the safe default (design decision 14).
            if (e.message?.contains("There is no configured VCS service for") == true) {
                return CheckResult(
                    Outcome.UNKNOWN,
                    reason = "Recorded repository URL is unresolvable by any configured VCS provider: ${target.targetId}",
                    reasonKind = ReasonKind.REGISTRY_DATA,
                )
            }
            // Ambiguous failure — could be system unavailable, credential error, or some other
            // unrecognised server-side error. Fail closed.
            return CheckResult(
                Outcome.UNKNOWN,
                reason = "VCS system could not be consulted: ${e.message}",
                reasonKind = ReasonKind.SYSTEM_UNAVAILABLE,
            )
        }
        return when (repo.archived) {
            true -> {
                val shared = sharingHelper.sharedWithForRepo(
                    VcsUrlCanonicalizer.canonicalize(target.targetId),
                    target.componentId,
                )
                CheckResult(Outcome.PASSED, sharedWith = shared)
            }
            false -> {
                val shared = sharingHelper.sharedWithForRepo(
                    VcsUrlCanonicalizer.canonicalize(target.targetId),
                    target.componentId,
                )
                if (shared.isNotEmpty()) {
                    CheckResult(Outcome.PASSED, sharedWith = shared)
                } else {
                    CheckResult(Outcome.FAILED)
                }
            }
            null -> CheckResult(
                Outcome.UNKNOWN,
                reason = "VCS system returned indeterminate archived state",
                reasonKind = ReasonKind.SYSTEM_UNAVAILABLE,
            )
        }
    }
}
