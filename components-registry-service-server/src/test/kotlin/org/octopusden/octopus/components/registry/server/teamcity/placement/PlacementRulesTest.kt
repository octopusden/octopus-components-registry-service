package org.octopusden.octopus.components.registry.server.teamcity.placement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Port of `test_placement_import.py` (ONB-001 one-off) onto the ONB-002 (TeamCity placement
 * sync) Kotlin engine — one test per Python case, same names, same fixtures translated to this
 * model's null-is-root convention (Python used `""`). The Python original had no `release-only` /
 * `partial` scope: this port additionally drops that fallback (the diff job reads compile
 * configurations only, per the design brief), folding what Python called "partial" into
 * [PlacementRowStatus.UNEXPRESSIBLE] — see [derive] kdoc.
 */
class ParseCheckoutRuleTest {
    @Test
    fun `recognised forms`() {
        assertEquals(PlacementValue(null, null), parseCheckoutRule(""))
        assertEquals(PlacementValue("app-one", null), parseCheckoutRule("+:. => app-one"))
        assertEquals(PlacementValue("core", "mapper"), parseCheckoutRule("+:mapper => core/mapper"))
        assertEquals(PlacementValue(null, "mapper"), parseCheckoutRule("+:mapper => mapper"))
        assertEquals(PlacementValue(null, "mapper"), parseCheckoutRule("+:mapper"))
    }

    @Test
    fun `unexpressible shapes`() {
        assertNull(parseCheckoutRule("+:a => b")) // remap
        assertNull(parseCheckoutRule("+:. => a/b")) // multi-segment checkout directory
        assertNull(parseCheckoutRule("+:. => x\n-:docs")) // several rules
        assertNull(parseCheckoutRule("-:docs"))
    }
}

class ParseWorkDirTest {
    @Test
    fun `recognised forms`() {
        assertEquals(WorkDirParse.Path(null), parseWorkDir("%teamcity.build.checkoutDir%"))
        assertEquals(WorkDirParse.Path(null), parseWorkDir(null))
        assertEquals(WorkDirParse.Path("core/mapper"), parseWorkDir("%teamcity.build.checkoutDir%/core/mapper"))
        assertEquals(WorkDirParse.Path("core"), parseWorkDir("core"))
    }

    @Test
    fun `unexpressible shapes`() {
        assertEquals(WorkDirParse.Unexpressible, parseWorkDir("%CUSTOMIZATION_APP_PATH%"))
        assertEquals(WorkDirParse.Unexpressible, parseWorkDir("/abs"))
        assertEquals(WorkDirParse.Unexpressible, parseWorkDir("a/../b"))
    }
}

class RepoKeyTest {
    @Test
    fun `scheme and case agnostic, host included`() {
        assertEquals("host/prj/app-one", repoKey("ssh://git@host/PRJ/App-One.git"))
        assertEquals("host/prj/app-one", repoKey("git@host:prj/app-one"))
    }

    @Test
    fun `the same path on a different host does not match`() {
        assertEquals(
            false,
            repoKey("ssh://host-a/prj/app-one.git") == repoKey("ssh://host-b/prj/app-one.git"),
        )
    }
}

/** Shared fixtures translated from the Python `ENTRIES` / `cfg()` helper. */
private val ENTRIES = listOf(
    PlacementRegistryEntry("app-one", "ssh://h/prj/app-one.git", "GIT", null, null),
    PlacementRegistryEntry("app-two", "ssh://h/prj/app-two.git", "GIT", null, null),
)

private fun compileConfig(
    vararg roots: Pair<String, String?>,
    workDir: String? = null,
    id: String = "bt${roots.hashCode()}",
): TcCompileConfig =
    TcCompileConfig(
        buildTypeId = id,
        vcsRootEntries = roots.map { (url, rules) -> TcVcsRootEntry(url, rules) },
        workDir = workDir,
    )

class DeriveTest {
    @Test
    fun `one build type attaching the same repository twice with disagreeing rules is unexpressible`() {
        // Regression: a naive last-wins map (Map.associate) over vcsRootEntries would silently
        // pick whichever rule happened to sort last, hiding this as a resolvable placement.
        val gateway = "ssh://h/prj/app-two.git"
        val app = "ssh://h/prj/app-one.git"
        val bt = compileConfig(gateway to "", app to "+:. => app-one", app to "+:. => other-name")
        val outcome = derive(DeriveInput(ENTRIES, listOf(bt), 0, emptyMap()))
        assertEquals(PlacementRowStatus.UNEXPRESSIBLE, outcome.status)
    }

    @Test
    fun `the same repository attached twice with textually different but equivalent rules still resolves`() {
        // `+:mapper` and `+:mapper => mapper` both parse to the same PlacementValue (Source Path
        // "mapper", checkout root) — comparing raw rule text would flag this as a false conflict.
        val gateway = "ssh://h/prj/app-two.git"
        val app = "ssh://h/prj/app-one.git"
        // gateway gets a Checkout Directory too, so only `app` is at the checkout root — two
        // entries both landing at the root (checkRules) would fail for an unrelated reason.
        val bt = compileConfig(gateway to "+:. => gw", app to "+:mapper", app to "+:mapper => mapper")
        val outcome = derive(DeriveInput(ENTRIES, listOf(bt), 0, emptyMap()))
        assertEquals(PlacementRowStatus.RESOLVED, outcome.status)
        // ENTRIES = [app-one, app-two]; `app` is app-one's vcsPath -> index 0.
        assertEquals(PlacementValue(null, "mapper"), outcome.perEntry[0])
    }

    @Test
    fun `comp-one shape resolves`() {
        val gateway = "ssh://h/prj/app-two.git"
        val app = "ssh://h/prj/app-one.git"
        val both = compileConfig(gateway to "", app to "+:. => app-one", id = "both")
        val branchCopy = compileConfig(gateway to "", id = "branchCopy")
        val outcome = derive(DeriveInput(ENTRIES, listOf(both, branchCopy), pausedCompileCount = 0, outsideRuledConfigCounts = emptyMap()))
        assertEquals(PlacementRowStatus.RESOLVED, outcome.status)
        assertEquals(mapOf(0 to PlacementValue("app-one", null), 1 to PlacementValue(null, null)), outcome.perEntry)
        assertNull(outcome.buildWorkingDirectory)
    }

    @Test
    fun `disagreeing configurations conflict`() {
        val gateway = "ssh://h/prj/app-two.git"
        val app = "ssh://h/prj/app-one.git"
        val a = compileConfig(gateway to "", app to "+:. => app-one", id = "a")
        val b = compileConfig(gateway to "+:. => gw", app to "+:. => app-one", id = "b")
        val outcome = derive(DeriveInput(ENTRIES, listOf(a, b), 0, emptyMap()))
        assertEquals(PlacementRowStatus.CONFLICT, outcome.status)
    }

    @Test
    fun `no compile configuration at all is no chain`() {
        val outcome = derive(DeriveInput(ENTRIES, emptyList(), 0, emptyMap()))
        assertEquals(PlacementRowStatus.NO_CHAIN, outcome.status)
    }

    @Test
    fun `a root never attached in any compile configuration is unexpressible`() {
        // Python called this "partial" (no release fallback here to complete it) — folded into
        // UNEXPRESSIBLE, since the row cannot be safely applied either way.
        val gateway = "ssh://h/prj/app-two.git"
        val onlyGateway = compileConfig(gateway to "")
        val outcome = derive(DeriveInput(ENTRIES, listOf(onlyGateway), 0, emptyMap()))
        assertEquals(PlacementRowStatus.UNEXPRESSIBLE, outcome.status)
    }

    @Test
    fun `two roots at the checkout root is unexpressible`() {
        val gateway = "ssh://h/prj/app-two.git"
        val app = "ssh://h/prj/app-one.git"
        val c = compileConfig(gateway to "", app to "")
        val outcome = derive(DeriveInput(ENTRIES, listOf(c), 0, emptyMap()))
        assertEquals(PlacementRowStatus.UNEXPRESSIBLE, outcome.status)
    }

    @Test
    fun `work dir that is not a plain path is unexpressible`() {
        val gateway = "ssh://h/prj/app-two.git"
        val app = "ssh://h/prj/app-one.git"
        val c = compileConfig(gateway to "", app to "+:. => app-one", workDir = "%CUSTOMIZATION_APP_PATH%")
        val outcome = derive(DeriveInput(ENTRIES, listOf(c), 0, emptyMap()))
        assertEquals(PlacementRowStatus.UNEXPRESSIBLE, outcome.status)
    }

    @Test
    fun `outside templates and compile paused signals win when there is no compile configuration`() {
        val outsideOnly = derive(DeriveInput(ENTRIES, emptyList(), 0, mapOf("X_Build" to 3)))
        assertEquals(PlacementRowStatus.OUTSIDE_TEMPLATES, outsideOnly.status)
        val pausedOnly = derive(DeriveInput(ENTRIES, emptyList(), pausedCompileCount = 2, outsideRuledConfigCounts = emptyMap()))
        assertEquals(PlacementRowStatus.COMPILE_PAUSED, pausedOnly.status)
    }

    @Test
    fun `every root in a checkout directory without a build working directory is unexpressible`() {
        val gateway = "ssh://h/prj/app-two.git"
        val app = "ssh://h/prj/app-one.git"
        val c = compileConfig(gateway to "+:. => gw", app to "+:. => app")
        val outcome = derive(DeriveInput(ENTRIES, listOf(c), 0, emptyMap()))
        assertEquals(PlacementRowStatus.UNEXPRESSIBLE, outcome.status)
    }

    @Test
    fun `compile wins over a second compile config attaching the first root without a rule`() {
        val pilot = listOf(
            PlacementRegistryEntry("main", "ssh://h/a/templates.git", "GIT", null, null),
            PlacementRegistryEntry("features", "ssh://h/b/plugins.git", "GIT", null, null),
        )
        val comp = compileConfig(
            "ssh://h/a/templates.git" to "+:. => core",
            "ssh://h/b/plugins.git" to "+:. => feature",
            workDir = "%teamcity.build.checkoutDir%/core/mapper",
        )
        val outcome = derive(DeriveInput(pilot, listOf(comp), 0, emptyMap()))
        assertEquals(PlacementRowStatus.RESOLVED, outcome.status)
        assertEquals(mapOf(0 to PlacementValue("core", null), 1 to PlacementValue("feature", null)), outcome.perEntry)
        assertEquals("core/mapper", outcome.buildWorkingDirectory)
    }

    @Test
    fun `check rules catches a build working directory outside every placed root`() {
        val result = mapOf(0 to PlacementValue("a", null), 1 to PlacementValue("b", null))
        assertEquals(true, checkRules(ENTRIES, result, "c/x").isNotEmpty())
        assertEquals(true, checkRules(ENTRIES, result, "a/x").isEmpty())
        // case-sensitive, as the registry itself compares the value
        assertEquals(true, checkRules(ENTRIES, result, "A/x").isNotEmpty())
    }
}
