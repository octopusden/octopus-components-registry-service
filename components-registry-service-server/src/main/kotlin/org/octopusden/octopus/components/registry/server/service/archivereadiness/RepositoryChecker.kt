package org.octopusden.octopus.components.registry.server.service.archivereadiness

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.dto.v4.Outcome
import org.octopusden.octopus.components.registry.server.dto.v4.ReasonKind
import org.octopusden.octopus.components.registry.server.util.VcsUrlCanonicalizer
import org.octopusden.octopus.vcsfacade.client.VcsFacadeClient
import org.octopusden.octopus.vcsfacade.client.common.exception.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

// SYS-047: depends (via SharingHelper) on beans that inject JPA repositories, so it must be
// dropped in no-db mode too — see ConditionalOnDatabaseEnabled's kdoc ("or another bean so
// annotated").
@ConditionalOnDatabaseEnabled
@Service
class RepositoryChecker(
    private val vcsFacadeClient: VcsFacadeClient?,
    private val sharingHelper: SharingHelper,
) : TargetChecker {
    private val log = LoggerFactory.getLogger(RepositoryChecker::class.java)

    // The plain Exception catch below is deliberate: this check must fail closed
    // to UNKNOWN on ANY failure from the VCS client, not just specific exception types — an
    // unanticipated exception type from a third-party client is itself evidence the system
    // couldn't be consulted reliably.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun check(target: CheckTarget): CheckResult {
        if (vcsFacadeClient == null) {
            log.info("REPOSITORY: skipped for {} — VCS not configured", target.targetId)
            return CheckResult(
                Outcome.UNKNOWN,
                reason = "VCS not configured",
                reasonKind = ReasonKind.NOT_CONFIGURED,
            )
        }
        val repo = try {
            vcsFacadeClient.getRepository(target.targetId)
        } catch (e: NotFoundException) {
            log.warn("REPOSITORY: {} — VCS system reported not found; cannot confirm absence vs. inaccessible", target.targetId)
            return CheckResult(
                Outcome.UNKNOWN,
                reason = "VCS system reported repository not found — cannot confirm this means the repository " +
                    "no longer exists rather than being inaccessible to the credential CRS uses: ${target.targetId}",
                reasonKind = ReasonKind.SYSTEM_UNAVAILABLE,
            )
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
                log.warn("REPOSITORY: {} is unresolvable by any configured VCS provider — registry data to correct", target.targetId)
                return CheckResult(
                    Outcome.UNKNOWN,
                    reason = "Recorded repository URL is unresolvable by any configured VCS provider: ${target.targetId}",
                    reasonKind = ReasonKind.REGISTRY_DATA,
                )
            }
            // Ambiguous failure — could be system unavailable, credential error, or some other
            // unrecognised server-side error. Fail closed.
            log.warn("REPOSITORY: {} could not be consulted: {}", target.targetId, e.message)
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
                CheckResult(Outcome.COMPLETED, sharedWith = shared)
            }
            false -> {
                val shared = sharingHelper.sharedWithForRepo(
                    VcsUrlCanonicalizer.canonicalize(target.targetId),
                    target.componentId,
                )
                if (shared.isNotEmpty()) {
                    CheckResult(Outcome.COMPLETED, sharedWith = shared)
                } else {
                    CheckResult(Outcome.NOT_COMPLETED)
                }
            }
            null -> {
                log.warn("REPOSITORY: {} — VCS system returned indeterminate archived state", target.targetId)
                CheckResult(
                    Outcome.UNKNOWN,
                    reason = "VCS system returned indeterminate archived state",
                    reasonKind = ReasonKind.SYSTEM_UNAVAILABLE,
                )
            }
        }
    }
}
