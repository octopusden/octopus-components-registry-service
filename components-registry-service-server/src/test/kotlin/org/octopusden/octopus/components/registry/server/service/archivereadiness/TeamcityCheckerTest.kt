package org.octopusden.octopus.components.registry.server.service.archivereadiness

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.octopusden.octopus.components.registry.server.dto.v4.Outcome
import org.octopusden.octopus.components.registry.server.dto.v4.ReasonKind
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
            TcDescendantResult.Found(setOf("TC_PROJECT_1"), setOf("TC_PROJECT_1")),
        )
        whenever(sharingHelper.sharedWithForTcProject(any(), any(), eq(componentId))).thenReturn(emptyList())
        val result = checker.check(target)
        assertThat(result.outcome).isEqualTo(Outcome.PASSED)
        assertThat(result.reasonKind).isNull()
    }

    @Test
    fun `unarchived project with no sharing yields FAILED`() {
        whenever(tcLookup.findDescendantsAndSelf("TC_PROJECT_1")).thenReturn(
            TcDescendantResult.Found(setOf("TC_PROJECT_1"), emptySet()),
        )
        whenever(sharingHelper.sharedWithForTcProject(any(), any(), eq(componentId))).thenReturn(emptyList())
        val result = checker.check(target)
        assertThat(result.outcome).isEqualTo(Outcome.FAILED)
        assertThat(result.reasonKind).isNull()
    }

    @Test
    fun `unarchived project shared with live component yields PASSED`() {
        whenever(tcLookup.findDescendantsAndSelf("TC_PROJECT_1")).thenReturn(
            TcDescendantResult.Found(setOf("TC_PROJECT_1"), emptySet()),
        )
        whenever(sharingHelper.sharedWithForTcProject(any(), any(), eq(componentId))).thenReturn(listOf("other"))
        val result = checker.check(target)
        assertThat(result.outcome).isEqualTo(Outcome.PASSED)
        assertThat(result.sharedWith).containsExactly("other")
        assertThat(result.reasonKind).isNull()
    }

    @Test
    fun `TC system unavailable yields UNKNOWN classified SYSTEM_UNAVAILABLE`() {
        whenever(tcLookup.findDescendantsAndSelf("TC_PROJECT_1")).thenReturn(TcDescendantResult.SystemUnavailable)
        val result = checker.check(target)
        assertThat(result.outcome).isEqualTo(Outcome.UNKNOWN)
        assertThat(result.reasonKind).isEqualTo(ReasonKind.SYSTEM_UNAVAILABLE)
    }

    @Test
    fun `absent project yields PASSED with no classification`() {
        whenever(tcLookup.findDescendantsAndSelf("TC_PROJECT_1")).thenReturn(
            TcDescendantResult.ProjectAbsent("not found"),
        )
        val result = checker.check(target)
        assertThat(result.outcome).isEqualTo(Outcome.PASSED)
        assertThat(result.sharedWith).isEmpty()
        assertThat(result.reasonKind).isNull()
    }
}
