package org.octopusden.octopus.components.registry.server.service.archivereadiness

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.octopusden.octopus.components.registry.server.dto.v4.Outcome
import java.util.UUID

class TeamcityCheckerTest {
    private val tcLookup = mock<TcDescendantLookup>()
    private val sharingHelper = mock<SharingHelper>()
    private val checker = TeamcityChecker(tcLookup, sharingHelper)
    private val componentId = UUID.randomUUID()
    private val target = CheckTarget("TC_PROJECT_1", componentId, "comp")

    @Test
    fun `archived project yields PASSED`() {
        whenever(tcLookup.findDescendantsAndSelf("TC_PROJECT_1")).thenReturn(
            TcDescendantResult.Found(setOf("TC_PROJECT_1"), setOf("TC_PROJECT_1"))
        )
        whenever(sharingHelper.sharedWithForTcProject(any(), any(), eq(componentId))).thenReturn(emptyList())
        assertThat(checker.check(target).outcome).isEqualTo(Outcome.PASSED)
    }

    @Test
    fun `unarchived project with no sharing yields FAILED`() {
        whenever(tcLookup.findDescendantsAndSelf("TC_PROJECT_1")).thenReturn(
            TcDescendantResult.Found(setOf("TC_PROJECT_1"), emptySet())
        )
        whenever(sharingHelper.sharedWithForTcProject(any(), any(), eq(componentId))).thenReturn(emptyList())
        assertThat(checker.check(target).outcome).isEqualTo(Outcome.FAILED)
    }

    @Test
    fun `unarchived project shared with live component yields PASSED`() {
        whenever(tcLookup.findDescendantsAndSelf("TC_PROJECT_1")).thenReturn(
            TcDescendantResult.Found(setOf("TC_PROJECT_1"), emptySet())
        )
        whenever(sharingHelper.sharedWithForTcProject(any(), any(), eq(componentId))).thenReturn(listOf("other"))
        val result = checker.check(target)
        assertThat(result.outcome).isEqualTo(Outcome.PASSED)
        assertThat(result.sharedWith).containsExactly("other")
    }

    @Test
    fun `TC system unavailable yields UNKNOWN`() {
        whenever(tcLookup.findDescendantsAndSelf("TC_PROJECT_1")).thenReturn(TcDescendantResult.SystemUnavailable)
        assertThat(checker.check(target).outcome).isEqualTo(Outcome.UNKNOWN)
    }

    @Test
    fun `absent project yields PASSED`() {
        whenever(tcLookup.findDescendantsAndSelf("TC_PROJECT_1")).thenReturn(
            TcDescendantResult.ProjectAbsent("not found")
        )
        val result = checker.check(target)
        assertThat(result.outcome).isEqualTo(Outcome.PASSED)
        assertThat(result.sharedWith).isEmpty()
    }
}
