package org.octopusden.octopus.components.registry.server.teamcity.placement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class VcsRootsComparisonTest {
    private val app = "ssh://h/prj/app-one.git"
    private val tooling = "ssh://h/tools/shared-tooling.git"
    private val appB = "ssh://h/prj/app-two.git"

    private fun config(
        id: String,
        vararg urls: String,
    ) = TcCompileConfig(id, urls.map { TcVcsRootEntry(it, "") }, null)

    @Test
    fun `matching roots produce no difference`() {
        val result = compareVcsRoots(listOf(app), listOf(config("bt1", app)))

        assertEquals(VcsRootsComparison(emptyList(), emptyList()), result)
    }

    @Test
    @DisplayName("SYS-099: a tooling root attached by TeamCity only is extra, with its build types")
    fun `SYS-099 a tooling root attached by TeamCity only is extra, with its build types`() {
        val result = compareVcsRoots(listOf(app), listOf(config("bt1", app, tooling), config("bt2", tooling, app)))

        assertEquals(listOf(ExtraVcsRoot("tools/shared-tooling", listOf("bt1", "bt2"))), result.extra)
        assertEquals(emptyList<String>(), result.missing)
    }

    @Test
    @DisplayName("SYS-099: a configuration attaching none of the component's repositories is not judged")
    fun `SYS-099 a configuration attaching none of the component's repositories is not judged`() {
        // bt2 belongs to a sibling component or an old version line: its roots are not "extra".
        val result = compareVcsRoots(listOf(app), listOf(config("bt1", app), config("bt2", appB, tooling)))

        assertEquals(emptyList<ExtraVcsRoot>(), result.extra)
    }

    @Test
    fun `a registry root no compile configuration attaches is missing`() {
        val result = compareVcsRoots(listOf(app, tooling), listOf(config("bt1", app)))

        assertEquals(emptyList<ExtraVcsRoot>(), result.extra)
        assertEquals(listOf(tooling), result.missing)
    }

    @Test
    fun `urls match through the canonical form, scheme and git suffix ignored`() {
        val result = compareVcsRoots(listOf(app), listOf(config("bt1", "git@h:prj/APP-one")))

        assertEquals(VcsRootsComparison(emptyList(), emptyList()), result)
    }

    @Test
    fun `without compile configurations there is nothing to compare`() {
        assertEquals(VcsRootsComparison(emptyList(), emptyList()), compareVcsRoots(listOf(app), emptyList()))
    }
}
