package org.octopusden.octopus.components.registry.server.controller

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever
import org.octopusden.octopus.components.registry.server.security.CurrentUserResolver
import org.octopusden.octopus.components.registry.server.service.MigrationLifecycleGate
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.CheckoutLayoutDiffResult
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.CheckoutLayoutSyncResult
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.TeamcityCheckoutLayoutDiffService
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.TeamcityCheckoutLayoutSyncService
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.impl.TeamcityCheckoutLayoutDiffJobServiceImpl
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.impl.TeamcityCheckoutLayoutSyncJobServiceImpl
import org.springframework.core.task.TaskExecutor
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID

/**
 * The REAL job services and controller behind
 * MockMvc, so the three distinct 409 bodies of `POST /sync` and the "latest completed Diff"
 * semantics of the report are asserted on the wire. The executor queues work instead of running it,
 * so a job stays RUNNING (and keeps the cross-kind gate) until the test says otherwise.
 */
class TeamcityCheckoutLayoutControllerV4ContractTest {
    private class ManualExecutor : TaskExecutor {
        private val queue = ArrayDeque<Runnable>()

        override fun execute(task: Runnable) {
            queue.addLast(task)
        }

        fun runNext() = queue.removeFirst().run()
    }

    private val base = "/rest/api/4/admin/teamcity-checkout-layout"
    private val gate = MigrationLifecycleGate()
    private val diffExecutor = ManualExecutor()
    private val syncExecutor = ManualExecutor()
    private val diffService = mock<TeamcityCheckoutLayoutDiffService>()
    private val syncService = mock<TeamcityCheckoutLayoutSyncService>()
    private lateinit var diffJobs: TeamcityCheckoutLayoutDiffJobServiceImpl
    private lateinit var syncJobs: TeamcityCheckoutLayoutSyncJobServiceImpl
    private lateinit var users: CurrentUserResolver
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        diffJobs = TeamcityCheckoutLayoutDiffJobServiceImpl(diffService, diffExecutor, gate)
        syncJobs = TeamcityCheckoutLayoutSyncJobServiceImpl(syncService, syncExecutor, gate)
        users = mock<CurrentUserResolver>()
        whenever(users.currentUsername()).thenReturn("alice")
        whenever(diffService.runDiff()).thenReturn(CheckoutLayoutDiffResult(Instant.now(), emptyList()))
        whenever(syncService.sync(any(), any(), any(), any())).thenReturn(CheckoutLayoutSyncResult("alice", 0, 0, 0, 0, emptyList()))
        mvc = MockMvcBuilders
            .standaloneSetup(TeamcityCheckoutLayoutControllerV4(diffJobs, syncJobs, users))
            .setControllerAdvice(ControllerExceptionHandler())
            .build()
    }

    /** Runs one Diff to completion and returns its id. */
    private fun completedDiff(): String {
        mvc.perform(post("$base/diff")).andExpect(status().isAccepted)
        diffExecutor.runNext()
        return diffJobs.current()!!.id
    }

    private fun startDiffLeftRunning() {
        mvc.perform(post("$base/diff")).andExpect(status().isAccepted)
    }

    private fun sync(diffId: String) =
        mvc.perform(
            post("$base/sync")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"diffId":"$diffId","componentIds":["${UUID.randomUUID()}"]}"""),
        )

    @Test
    @DisplayName("SYS-102: the report stays readable while a new Diff runs")
    fun `SYS-102 the report stays readable while a new Diff runs`() {
        val first = completedDiff()
        startDiffLeftRunning()

        mvc
            .perform(get("$base/diff/report.json"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.diffId").value(first))
    }

    @Test
    @DisplayName("SYS-102: a failed Diff keeps the previous report")
    fun `SYS-102 a failed Diff keeps the previous report`() {
        val first = completedDiff()
        whenever(diffService.runDiff()).thenThrow(IllegalStateException("tc down"))
        startDiffLeftRunning()
        diffExecutor.runNext()

        mvc
            .perform(get("$base/diff/report.json"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.diffId").value(first))
    }

    @Test
    @DisplayName("SYS-102: a Sync naming the last completed Diff is accepted when a later Diff failed")
    fun `SYS-102 a Sync naming the last completed Diff is accepted when a later Diff failed`() {
        val first = completedDiff()
        whenever(diffService.runDiff()).thenThrow(IllegalStateException("tc down"))
        startDiffLeftRunning()
        diffExecutor.runNext()

        sync(first).andExpect(status().isAccepted)
    }

    @Test
    @DisplayName("SYS-102: a Sync while a Diff runs answers the gate's conflict, even with a stale diffId")
    fun `SYS-102 a Sync while a Diff runs answers the gate's conflict, even with a stale diffId`() {
        completedDiff()
        startDiffLeftRunning()

        sync("not-the-latest")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("tc-checkout-layout-diff-running"))
            .andExpect(jsonPath("$.activeKind").value("TC_CHECKOUT_LAYOUT_DIFF"))
            .andExpect(jsonPath("$.activeJobId").value(diffJobs.current()!!.id))
    }

    @Test
    @DisplayName("SYS-102: a Sync while another admin job runs answers the gate's conflict")
    fun `SYS-102 a Sync while another admin job runs answers the gate's conflict`() {
        val first = completedDiff()
        gate.tryClaim(MigrationLifecycleGate.JobKind.TC_RESYNC, "resync-1")

        sync(first)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("tc-resync-running"))
    }

    @Test
    @DisplayName("SYS-102: a Sync while a Sync runs answers the running job (kind job)")
    fun `SYS-102 a Sync while a Sync runs answers the running job (kind job)`() {
        val first = completedDiff()
        sync(first).andExpect(status().isAccepted)

        sync(first)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.kind").value("job"))
            .andExpect(jsonPath("$.state").value("RUNNING"))
    }

    @Test
    @DisplayName("SYS-102: a stale diffId on an idle gate answers the checkout-layout-diff-stale code")
    fun `SYS-102 a stale diffId on an idle gate answers the checkout-layout-diff-stale code`() {
        completedDiff()
        val second = completedDiff()

        sync("the-first-one")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("checkout-layout-diff-stale"))
            .andExpect(jsonPath("$.errorMessage").value("diff replaced, re-run Diff"))
        sync(second).andExpect(status().isAccepted)
    }

    @Test
    @DisplayName("SYS-102: a Sync before any Diff completed answers the checkout-layout-diff-stale code")
    fun `SYS-102 a Sync before any Diff completed answers the checkout-layout-diff-stale code`() {
        sync("nothing-yet")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("checkout-layout-diff-stale"))
    }

    @Test
    @DisplayName("SYS-102: the stale check reads the last completed Diff under the gate, not before it")
    fun `SYS-102 the stale check reads the last completed Diff under the gate, not before it`() {
        // A Diff completing between the request's read and the Sync's claim must make the Sync
        // stale. Simulated with a spy: the old Diff is "last completed" while the gate is free,
        // the newer one once the Sync holds the gate.
        val first = completedDiff()
        val firstState = diffJobs.lastCompleted()!!
        completedDiff()
        val secondState = diffJobs.lastCompleted()!!
        val racing = spy(diffJobs)
        doAnswer { if (gate.current()?.kind == MigrationLifecycleGate.JobKind.TC_CHECKOUT_LAYOUT_SYNC) secondState else firstState }
            .whenever(racing)
            .lastCompleted()
        val racingMvc = MockMvcBuilders
            .standaloneSetup(TeamcityCheckoutLayoutControllerV4(racing, syncJobs, users))
            .setControllerAdvice(ControllerExceptionHandler())
            .build()

        racingMvc
            .perform(
                post("$base/sync")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"diffId":"$first","componentIds":["${UUID.randomUUID()}"]}"""),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("checkout-layout-diff-stale"))
    }
}
