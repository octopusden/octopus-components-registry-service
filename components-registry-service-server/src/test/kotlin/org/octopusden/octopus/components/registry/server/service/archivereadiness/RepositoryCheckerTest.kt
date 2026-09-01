package org.octopusden.octopus.components.registry.server.service.archivereadiness

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.octopusden.octopus.components.registry.server.dto.v4.Outcome
import org.octopusden.octopus.vcsfacade.client.VcsFacadeClient
import org.octopusden.octopus.vcsfacade.client.common.dto.Repository
import org.octopusden.octopus.vcsfacade.client.common.exception.ArgumentsNotCompatibleException
import org.octopusden.octopus.vcsfacade.client.common.exception.NotFoundException
import org.octopusden.octopus.vcsfacade.client.common.exception.VcsFacadeException
import java.util.UUID

class RepositoryCheckerTest {
    private val vcsFacadeClient = mock<VcsFacadeClient>()
    private val sharingHelper = mock<SharingHelper>()
    private val checker = RepositoryChecker(vcsFacadeClient, sharingHelper)
    private val componentId = UUID.randomUUID()
    private val target = CheckTarget("ssh://git.example.com/repo.git", componentId, "my-comp")

    private fun repo(archived: Boolean?) =
        Repository("ssh://git.example.com/repo.git", "https://git.example.com/repo", null, archived)

    @Test
    fun `archived true yields PASSED`() {
        whenever(vcsFacadeClient.getRepository(target.targetId)).thenReturn(repo(true))
        whenever(sharingHelper.sharedWithForRepo(any(), eq(componentId))).thenReturn(emptyList())
        assertThat(checker.check(target).outcome).isEqualTo(Outcome.PASSED)
    }

    @Test
    fun `archived false with no sharing yields FAILED`() {
        whenever(vcsFacadeClient.getRepository(target.targetId)).thenReturn(repo(false))
        whenever(sharingHelper.sharedWithForRepo(any(), eq(componentId))).thenReturn(emptyList())
        assertThat(checker.check(target).outcome).isEqualTo(Outcome.FAILED)
    }

    @Test
    fun `archived false with live sharing yields PASSED`() {
        whenever(vcsFacadeClient.getRepository(target.targetId)).thenReturn(repo(false))
        whenever(sharingHelper.sharedWithForRepo(any(), eq(componentId))).thenReturn(listOf("other-comp"))
        val result = checker.check(target)
        assertThat(result.outcome).isEqualTo(Outcome.PASSED)
        assertThat(result.sharedWith).containsExactly("other-comp")
    }

    @Test
    fun `archived null yields UNKNOWN`() {
        whenever(vcsFacadeClient.getRepository(target.targetId)).thenReturn(repo(null))
        assertThat(checker.check(target).outcome).isEqualTo(Outcome.UNKNOWN)
    }

    @Test
    fun `absent repository (NotFoundException) yields PASSED`() {
        whenever(vcsFacadeClient.getRepository(target.targetId))
            .thenThrow(NotFoundException("Repository not found"))
        val result = checker.check(target)
        assertThat(result.outcome).isEqualTo(Outcome.PASSED)
        assertThat(result.sharedWith).isEmpty()
    }

    @Test
    fun `generic exception (not NotFoundException) yields UNKNOWN, not treated as absence`() {
        // Locks the catch-order: NotFoundException before the generic Exception catch. Nothing
        // would fail today if a future edit broke that ordering without this regression test.
        whenever(vcsFacadeClient.getRepository(target.targetId))
            .thenThrow(RuntimeException("connection reset"))
        val result = checker.check(target)
        assertThat(result.outcome).isEqualTo(Outcome.UNKNOWN)
    }

    @Test
    fun `bare VcsFacadeException (not a NotFoundException subtype) yields UNKNOWN, not treated as absence`() {
        whenever(vcsFacadeClient.getRepository(target.targetId))
            .thenThrow(VcsFacadeException("unexpected server error"))
        val result = checker.check(target)
        assertThat(result.outcome).isEqualTo(Outcome.UNKNOWN)
    }

    @Test
    fun `unresolvable URL (ArgumentsNotCompatibleException) yields UNKNOWN naming the URL, not a VCS outage`() {
        whenever(vcsFacadeClient.getRepository(target.targetId))
            .thenThrow(ArgumentsNotCompatibleException("no provider configured for this URL"))
        val result = checker.check(target)
        assertThat(result.outcome).isEqualTo(Outcome.UNKNOWN)
        assertThat(result.reason).contains(target.targetId)
        assertThat(result.reason).doesNotContain("VCS system could not be consulted")
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
