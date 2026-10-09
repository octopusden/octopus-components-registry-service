package org.octopusden.octopus.components.registry.server.controller

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever
import org.octopusden.octopus.components.registry.server.security.CurrentUserResolver
import org.octopusden.octopus.components.registry.server.service.MigrationLifecycleGate
import org.octopusden.octopus.components.registry.server.teamcity.placement.PlacementDiffResult
import org.octopusden.octopus.components.registry.server.teamcity.placement.PlacementSyncResult
import org.octopusden.octopus.components.registry.server.teamcity.placement.TeamcityPlacementDiffService
import org.octopusden.octopus.components.registry.server.teamcity.placement.TeamcityPlacementSyncService
import org.octopusden.octopus.components.registry.server.teamcity.placement.impl.TeamcityPlacementDiffJobServiceImpl
import org.octopusden.octopus.components.registry.server.teamcity.placement.impl.TeamcityPlacementSyncJobServiceImpl
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
 * Owner review of PR #510 (findings 4 and 5): the REAL job services and controller behind
 * MockMvc, so the three distinct 409 bodies of `POST /sync` and the "latest completed Diff"
 * semantics of the report are asserted on the wire. The executor queues work instead of running it,
 * so a job stays RUNNING (and keeps the cross-kind gate) until the test says otherwise.
 */
class TeamcityPlacementControllerV4ContractTest {
    private class ManualExecutor : TaskExecutor {
        private val queue = ArrayDeque<Runnable>()

        override fun execute(task: Runnable) {
            queue.addLast(task)
        }

        fun runNext() = queue.removeFirst().run()
    }

    private val base = "/rest/api/4/admin/teamcity-placement"
    private val gate = MigrationLifecycleGate()
    private val diffExecutor = ManualExecutor()
    private val syncExecutor = ManualExecutor()
    private val diffService = mock<TeamcityPlacementDiffService>()
    private val syncService = mock<TeamcityPlacementSyncService>()
    private lateinit var diffJobs: TeamcityPlacementDiffJobServiceImpl
    private lateinit var syncJobs: TeamcityPlacementSyncJobServiceImpl
    private lateinit var users: CurrentUserResolver
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        diffJobs = TeamcityPlacementDiffJobServiceImpl(diffService, diffExecutor, gate)
        syncJobs = TeamcityPlacementSyncJobServiceImpl(syncService, syncExecutor, gate)
        users = mock<CurrentUserResolver>()
        whenever(users.currentUsername()).thenReturn("alice")
        whenever(diffService.runDiff()).thenReturn(PlacementDiffResult(Instant.now(), emptyList()))
        whenever(syncService.sync(any(), any(), any(), any())).thenReturn(PlacementSyncResult("alice", 0, 0, 0, 0, emptyList()))
        mvc = MockMvcBuilders
            .standaloneSetup(TeamcityPlacementControllerV4(diffJobs, syncJobs, users))
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
    fun `the report stays readable while a new Diff runs (RED)`() {
        val first = completedDiff()
        startDiffLeftRunning()

        mvc
            .perform(get("$base/diff/report.json"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.diffId").value(first))
    }

    @Test
    fun `a failed Diff keeps the previous report (RED)`() {
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
    fun `a Sync naming the last completed Diff is accepted when a later Diff failed (RED)`() {
        val first = completedDiff()
        whenever(diffService.runDiff()).thenThrow(IllegalStateException("tc down"))
        startDiffLeftRunning()
        diffExecutor.runNext()

        sync(first).andExpect(status().isAccepted)
    }

    @Test
    fun `a Sync while a Diff runs answers the gate's conflict, even with a stale diffId (RED)`() {
        completedDiff()
        startDiffLeftRunning()

        sync("not-the-latest")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("tc-placement-diff-running"))
            .andExpect(jsonPath("$.activeKind").value("TC_PLACEMENT_DIFF"))
            .andExpect(jsonPath("$.activeJobId").value(diffJobs.current()!!.id))
    }

    @Test
    fun `a Sync while another admin job runs answers the gate's conflict`() {
        val first = completedDiff()
        gate.tryClaim(MigrationLifecycleGate.JobKind.TC_RESYNC, "resync-1")

        sync(first)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("tc-resync-running"))
    }

    @Test
    fun `a Sync while a Sync runs answers the running job (kind job)`() {
        val first = completedDiff()
        sync(first).andExpect(status().isAccepted)

        sync(first)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.kind").value("job"))
            .andExpect(jsonPath("$.state").value("RUNNING"))
    }

    @Test
    fun `a stale diffId on an idle gate answers the placement-diff-stale code (RED)`() {
        completedDiff()
        val second = completedDiff()

        sync("the-first-one")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("placement-diff-stale"))
            .andExpect(jsonPath("$.errorMessage").value("diff replaced, re-run Diff"))
        sync(second).andExpect(status().isAccepted)
    }

    @Test
    fun `a Sync before any Diff completed answers the placement-diff-stale code (RED)`() {
        sync("nothing-yet")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("placement-diff-stale"))
    }

    @Test
    fun `the stale check reads the last completed Diff under the gate, not before it (Codex re-review, RED)`() {
        // A Diff completing between the request's read and the Sync's claim must make the Sync
        // stale. Simulated with a spy: the old Diff is "last completed" while the gate is free,
        // the newer one once the Sync holds the gate.
        val first = completedDiff()
        val firstState = diffJobs.lastCompleted()!!
        completedDiff()
        val secondState = diffJobs.lastCompleted()!!
        val racing = spy(diffJobs)
        doAnswer { if (gate.current()?.kind == MigrationLifecycleGate.JobKind.TC_PLACEMENT_SYNC) secondState else firstState }
            .whenever(racing)
            .lastCompleted()
        val racingMvc = MockMvcBuilders
            .standaloneSetup(TeamcityPlacementControllerV4(racing, syncJobs, users))
            .setControllerAdvice(ControllerExceptionHandler())
            .build()

        racingMvc
            .perform(
                post("$base/sync")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"diffId":"$first","componentIds":["${UUID.randomUUID()}"]}"""),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("placement-diff-stale"))
    }
}
