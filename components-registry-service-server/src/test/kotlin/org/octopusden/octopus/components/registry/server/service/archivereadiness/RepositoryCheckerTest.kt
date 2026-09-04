package org.octopusden.octopus.components.registry.server.service.archivereadiness

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.octopusden.octopus.components.registry.server.dto.v4.Outcome
import org.octopusden.octopus.components.registry.server.dto.v4.ReasonKind
import org.octopusden.octopus.vcsfacade.client.VcsFacadeClient
import org.octopusden.octopus.vcsfacade.client.common.dto.Repository
import org.octopusden.octopus.vcsfacade.client.common.exception.NotFoundException
import org.octopusden.octopus.vcsfacade.client.common.exception.VcsFacadeException
import java.util.UUID

class RepositoryCheckerTest {
    private val vcsFacadeClient = mock<VcsFacadeClient>()
    private val sharingHelper = mock<SharingHelper>()
    private val checker = RepositoryChecker(vcsFacadeClient, sharingHelper)
    private val componentId = UUID.randomUUID()
    private val target = CheckTarget("ssh://git.example.com/repo.git", componentId, "my-comp")

    private fun repo(archived: Boolean?) = Repository("ssh://git.example.com/repo.git", "https://git.example.com/repo", null, archived)

    @Test
    fun `archived true yields COMPLETED`() {
        whenever(vcsFacadeClient.getRepository(target.targetId)).thenReturn(repo(true))
        whenever(sharingHelper.sharedWithForRepo(any(), eq(componentId))).thenReturn(emptyList())
        val result = checker.check(target)
        assertThat(result.outcome).isEqualTo(Outcome.COMPLETED)
        assertThat(result.reasonKind).isNull()
    }

    @Test
    fun `archived false with no sharing yields NOT_COMPLETED`() {
        whenever(vcsFacadeClient.getRepository(target.targetId)).thenReturn(repo(false))
        whenever(sharingHelper.sharedWithForRepo(any(), eq(componentId))).thenReturn(emptyList())
        val result = checker.check(target)
        assertThat(result.outcome).isEqualTo(Outcome.NOT_COMPLETED)
        assertThat(result.reasonKind).isNull()
    }

    @Test
    fun `archived false with live sharing yields COMPLETED`() {
        whenever(vcsFacadeClient.getRepository(target.targetId)).thenReturn(repo(false))
        whenever(sharingHelper.sharedWithForRepo(any(), eq(componentId))).thenReturn(listOf("other-comp"))
        val result = checker.check(target)
        assertThat(result.outcome).isEqualTo(Outcome.COMPLETED)
        assertThat(result.sharedWith).containsExactly("other-comp")
        assertThat(result.reasonKind).isNull()
    }

    @Test
    fun `archived null yields UNKNOWN classified SYSTEM_UNAVAILABLE`() {
        whenever(vcsFacadeClient.getRepository(target.targetId)).thenReturn(repo(null))
        val result = checker.check(target)
        assertThat(result.outcome).isEqualTo(Outcome.UNKNOWN)
        assertThat(result.reasonKind).isEqualTo(ReasonKind.SYSTEM_UNAVAILABLE)
    }

    @Test
    fun `absent repository (NotFoundException) yields UNKNOWN, not COMPLETED — cannot confirm absence vs. inaccessible`() {
        // A hosting platform may answer 404 for a private, inaccessible repository the same way
        // it does for one that genuinely no longer exists (design.md decision 11/12) — trusting
        // this as confirmed absence would let a permission problem silently pass this check.
        whenever(vcsFacadeClient.getRepository(target.targetId))
            .thenThrow(NotFoundException("Repository not found"))
        val result = checker.check(target)
        assertThat(result.outcome).isEqualTo(Outcome.UNKNOWN)
        assertThat(result.sharedWith).isEmpty()
        assertThat(result.reasonKind).isEqualTo(ReasonKind.SYSTEM_UNAVAILABLE)
    }

    @Test
    fun `generic exception (not NotFoundException) yields UNKNOWN classified SYSTEM_UNAVAILABLE, not treated as absence`() {
        // Locks the catch-order: NotFoundException before the generic Exception catch. Nothing
        // would fail today if a future edit broke that ordering without this regression test.
        whenever(vcsFacadeClient.getRepository(target.targetId))
            .thenThrow(RuntimeException("connection reset"))
        val result = checker.check(target)
        assertThat(result.outcome).isEqualTo(Outcome.UNKNOWN)
        assertThat(result.reasonKind).isEqualTo(ReasonKind.SYSTEM_UNAVAILABLE)
    }

    @Test
    fun `bare VcsFacadeException with an unrelated message yields UNKNOWN via the generic reason, classified SYSTEM_UNAVAILABLE`() {
        // Guards against the message-substring check firing on every exception: an unrelated
        // message must still fall through to the old generic "system could not be consulted"
        // reason, not be misread as "URL unresolvable by any configured VCS provider".
        whenever(vcsFacadeClient.getRepository(target.targetId))
            .thenThrow(VcsFacadeException("unexpected server error"))
        val result = checker.check(target)
        assertThat(result.outcome).isEqualTo(Outcome.UNKNOWN)
        assertThat(result.reason).contains("VCS system could not be consulted")
        assertThat(result.reason).doesNotContain("unresolvable")
        assertThat(result.reasonKind).isEqualTo(ReasonKind.SYSTEM_UNAVAILABLE)
    }

    @Test
    fun `unresolvable URL yields UNKNOWN naming the URL, classified REGISTRY_DATA`() {
        // The real vcs-facade server (pinned 3.0.36) throws a plain IllegalStateException with
        // this exact message from VcsManagerImpl.getVcsServiceForSshUrl when no configured VCS
        // provider matches the URL. It has no dedicated @ExceptionHandler, so it falls to the
        // catch-all handler (VcsFacadeErrorCode.OTHER) and the client decodes it back into a bare
        // VcsFacadeException — not ArgumentsNotCompatibleException, not any other specific type.
        whenever(vcsFacadeClient.getRepository(target.targetId))
            .thenThrow(VcsFacadeException("There is no configured VCS service for '${target.targetId}'"))
        val result = checker.check(target)
        assertThat(result.outcome).isEqualTo(Outcome.UNKNOWN)
        assertThat(result.reason).contains(target.targetId)
        assertThat(result.reason).doesNotContain("VCS system could not be consulted")
        assertThat(result.reasonKind).isEqualTo(ReasonKind.REGISTRY_DATA)
    }

    @Test
    fun `stored vcsPath passed to getRepository unmodified`() {
        val rawUrl = "SSH://GIT.EXAMPLE.COM/Repo.GIT"
        val rawTarget = CheckTarget(rawUrl, componentId, "comp")
        whenever(vcsFacadeClient.getRepository(rawUrl)).thenReturn(repo(true))
        whenever(sharingHelper.sharedWithForRepo(any(), any())).thenReturn(emptyList())
        checker.check(rawTarget)
        verify(vcsFacadeClient).getRepository(rawUrl)
    }
}
