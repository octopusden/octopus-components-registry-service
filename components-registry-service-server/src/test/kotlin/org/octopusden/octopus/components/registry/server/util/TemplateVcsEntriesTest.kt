package org.octopusden.octopus.components.registry.server.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.octopusden.octopus.components.registry.server.model.TemplateFieldKind
import org.octopusden.octopus.components.registry.server.support.EXAMPLE_DEFAULTS
import org.octopusden.octopus.components.registry.server.support.TEMPLATE_ID
import org.octopusden.octopus.components.registry.server.support.at
import org.octopusden.octopus.components.registry.server.support.designExampleProperties
import org.octopusden.octopus.components.registry.server.support.exampleTemplate
import org.octopusden.octopus.components.registry.server.support.flatten

private const val MULTI_VCS = "required with more than one VCS entry"
private const val GAP = "VCS entries are numbered from 0 without gaps"
private const val WORKING_DIRECTORY = "baseConfiguration.buildWorkingDirectory"

class TemplateVcsEntriesTest {
    private val first = linkedMapOf<String, Any>("vcsPath" to "ssh://git@git.example.com/plugin.git")

    private fun further(dir: String) =
        linkedMapOf<String, Any>(
            "vcsPath" to "ssh://git@git.example.com/$dir.git",
            "branch" to "develop",
            "tag" to "$dir-\$version",
            "checkoutDirectory" to dir,
        )

    private fun problems(
        vcsEntries: List<Map<String, Any>>,
        change: MutableMap<String, Any>.() -> Unit = {},
    ): List<String> {
        val template =
            exampleTemplate().apply {
                at("fields.baseConfiguration")["vcsEntries"] = vcsEntries
                change()
            }
        return problems(flatten(template, TEMPLATE_ID))
    }

    private fun problems(flat: Map<String, String>): List<String> =
        ComponentProfileParser
            .parse(designExampleProperties() + flat, EXAMPLE_DEFAULTS)
            .entries
            .single { it.id == TEMPLATE_ID }
            .problems

    private fun assertProblem(
        path: String,
        message: String,
        problems: List<String>,
    ) {
        val key = "$TEMPLATE_ID.fields.$path:"
        assertTrue(problems.any { it.startsWith(key) && message in it }, "expected '$message' on '$key', got $problems")
    }

    @Test
    @DisplayName("SYS-110: a template with three full VCS entries loads live")
    fun `SYS-110 several vcs entries are live`() {
        assertEquals(emptyList<String>(), problems(listOf(first, further("ui"), further("docs"))))
    }

    @ParameterizedTest(name = "without {0}")
    @ValueSource(strings = ["checkoutDirectory", "vcsPath"])
    @DisplayName("SYS-110: a VCS entry after the first without its checkoutDirectory or vcsPath is a problem naming it")
    fun `SYS-110 further vcs entry needs its own fields`(field: String) {
        val problems = problems(listOf(first, further("ui"), further("docs") - field))

        assertProblem("baseConfiguration.vcsEntries[2].$field", MULTI_VCS, problems)
    }

    @Test
    @DisplayName("SYS-110: a further VCS entry's checkoutDirectory from an optional parameter is a problem naming it")
    fun `SYS-110 optional parameter does not produce the checkout directory`() {
        val problems =
            problems(listOf(first, further("ui") + ("checkoutDirectory" to "{{ UI_DIR }}"))) {
                at("parameters")["UI_DIR"] = linkedMapOf("label" to "UI directory", "type" to "text", "required" to "false")
            }

        assertProblem("baseConfiguration.vcsEntries[1].checkoutDirectory", MULTI_VCS, problems)
    }

    @Test
    @DisplayName("SYS-110: a further VCS entry needs the first entry's vcsPath, even for a build system without VCS")
    fun `SYS-110 further vcs entry needs the first`() {
        val problems =
            problems(listOf(linkedMapOf<String, Any>("branch" to "main"), further("ui"))) {
                at("fields.baseConfiguration.build")["buildSystem"] = "PROVIDED"
            }

        assertProblem("baseConfiguration.vcsEntries[0].vcsPath", MULTI_VCS, problems)
    }

    @Test
    @DisplayName("SYS-110: a missing first vcsPath the build system requires is one problem, also with further VCS entries")
    fun `SYS-110 missing first vcs path is one problem`() {
        val problems = problems(listOf(linkedMapOf<String, Any>("branch" to "main"), further("ui"))) { remove("overridable") }

        assertEquals(1, problems.size, "problems: $problems")
        assertTrue(problems.single().startsWith("$TEMPLATE_ID.fields.baseConfiguration.vcsEntries[0].vcsPath:"), "problems: $problems")
    }

    @Test
    @DisplayName("SYS-110: a gap in the VCS entry indexes is one problem naming the first missing entry")
    fun `SYS-110 gap in vcs entries`() {
        val flat =
            flatten(exampleTemplate(), TEMPLATE_ID) +
                flatten(further("docs"), "$TEMPLATE_ID.fields.baseConfiguration.vcsEntries[2]")

        val problems = problems(flat)

        assertEquals(1, problems.size, "problems: $problems")
        assertProblem("baseConfiguration.vcsEntries[1]", GAP, problems)
    }

    @Test
    @DisplayName("SYS-110: a VCS entry with a very large index costs one gap problem, not one per missing index")
    fun `SYS-110 large index is one gap`() {
        val flat =
            flatten(exampleTemplate(), TEMPLATE_ID) +
                flatten(further("ui"), "$TEMPLATE_ID.fields.baseConfiguration.vcsEntries[1]") +
                flatten(further("docs"), "$TEMPLATE_ID.fields.baseConfiguration.vcsEntries[100000000]")

        val problems = problems(flat)

        assertEquals(1, problems.size, "problems: $problems")
        assertProblem("baseConfiguration.vcsEntries[2]", GAP, problems)
    }

    @Test
    @DisplayName("SYS-110: a VCS entry index too large for a number is a problem naming it, as for any template list")
    fun `SYS-110 index too large`() {
        val flat =
            flatten(exampleTemplate(), TEMPLATE_ID) +
                flatten(further("docs"), "$TEMPLATE_ID.fields.baseConfiguration.vcsEntries[99999999999]")

        assertProblem("baseConfiguration.vcsEntries[99999999999].vcsPath", "the list index is too large", problems(flat))
    }

    @Test
    @DisplayName("SYS-110: an entry whose fields all fail to parse is reported as such, not also as a gap")
    fun `SYS-110 unparsed vcs entry is no gap`() {
        val unparsed = linkedMapOf<String, Any>("vcsPath" to "{{ UI | nofilter }}", "checkoutDirectory" to "{{ UI | nofilter }}")

        val problems = problems(listOf(first, unparsed, further("docs")))

        assertTrue(problems.any { it.startsWith("$TEMPLATE_ID.fields.baseConfiguration.vcsEntries[1].vcsPath:") }, "problems: $problems")
        assertFalse(problems.any { GAP in it }, "problems: $problems")
    }

    @Test
    @DisplayName(
        "SYS-110: a template with one VCS entry needs no checkout directory, and may set one with a build working directory; " +
            "for a build system without VCS it needs no vcsPath",
    )
    fun `SYS-110 one vcs entry with or without a checkout directory`() {
        assertEquals(emptyList<String>(), problems(listOf(first)))
        assertEquals(
            emptyList<String>(),
            problems(listOf(linkedMapOf<String, Any>("branch" to "main"))) {
                at("fields.baseConfiguration.build")["buildSystem"] = "PROVIDED"
                remove("overridable")
            },
        )
        assertEquals(
            emptyList<String>(),
            problems(listOf(first + ("checkoutDirectory" to "plugin"))) {
                at("fields.baseConfiguration")["buildWorkingDirectory"] = "plugin/app"
            },
        )
    }

    @Test
    @DisplayName("SYS-110: the first VCS entry's checkoutDirectory needs a buildWorkingDirectory")
    fun `SYS-110 first checkout directory needs a build working directory`() {
        val problems = problems(listOf(first + ("checkoutDirectory" to "plugin"), further("ui")))

        assertProblem(WORKING_DIRECTORY, "required when the first VCS entry has a checkoutDirectory", problems)
    }

    @Test
    @DisplayName("SYS-110: every VCS entry with a checkoutDirectory and a buildWorkingDirectory loads live")
    fun `SYS-110 every checkout directory with a build working directory is live`() {
        val problems =
            problems(listOf(first + ("checkoutDirectory" to "plugin"), further("ui"))) {
                at("fields.baseConfiguration")["buildWorkingDirectory"] = "plugin"
            }

        assertEquals(emptyList<String>(), problems)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "baseConfiguration.vcsEntries[0].checkoutDirectory",
            "baseConfiguration.vcsEntries[1].vcsPath",
            "baseConfiguration.vcsEntries[1].branch",
            "baseConfiguration.vcsEntries[7].tag",
            "baseConfiguration.vcsEntries[100].checkoutDirectory",
            "baseConfiguration.vcsEntries[12345].vcsPath",
            WORKING_DIRECTORY,
        ],
    )
    @DisplayName("SYS-110: the VCS entry fields and buildWorkingDirectory are template free text, not profile rule paths")
    fun `SYS-110 template-only vcs paths`(path: String) {
        assertEquals(TemplateFieldKind.FREE_TEXT, TemplateFields.kind(path))
        assertFalse(path in CreateRequestPaths.PATHS, "$path should not be a rule path")
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "baseConfiguration.vcsEntries[01].vcsPath",
            "baseConfiguration.vcsEntries[99999999999].vcsPath",
            "baseConfiguration.vcsEntries[1].hotfixBranch",
        ],
    )
    @DisplayName("a VCS entry path with a leading zero, an index too large for a number or another field is not a template field")
    fun otherVcsPaths(path: String) {
        assertNull(TemplateFields.kind(path))
    }
}
