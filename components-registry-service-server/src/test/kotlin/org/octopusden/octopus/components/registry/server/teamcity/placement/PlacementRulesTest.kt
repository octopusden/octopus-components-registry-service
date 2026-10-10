package org.octopusden.octopus.components.registry.server.teamcity.placement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pure placement-derivation engine of the ONB-002 (TeamCity placement sync) Diff job. The model's
 * convention is that a null path means the checkout root. See the [derive] kdoc for the scope.
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
    fun `a TeamCity property reference is unexpressible -- the only real parse-shape failure`() {
        assertEquals(WorkDirParse.Unexpressible, parseWorkDir("%CUSTOMIZATION_APP_PATH%"))
    }

    @Test
    fun `an absolute path or a segment CRS validation would reject still PARSES`() {
        // A leading "/" and a ".." segment are CRS VALIDATION rules
        // (VcsPlacementValidator.validateBuildWorkingDirectory's isPlainRelativePath), not
        // unparseable SHAPES -- parseWorkDir must not pre-empt that check the way the way a
        // pre-validating engine would. These parse to a plain Path so the Diff service's own
        // VcsPlacementValidator call can classify them INVALID with its own message, instead of
        // the pure engine downgrading to UNEXPRESSIBLE first.
        assertEquals(WorkDirParse.Path("/abs"), parseWorkDir("/abs"))
        assertEquals(WorkDirParse.Path("a/../b"), parseWorkDir("a/../b"))
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

/** Shared fixtures. */
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
    fun `one build type attaching the same repository twice with disagreeing rules is a conflict`() {
        // Regression: a naive last-wins map (Map.associate) over vcsRootEntries would silently
        // pick whichever rule happened to sort last, hiding this as a resolvable placement.
        // A genuine disagreement (two DIFFERENT resolvable
        // interpretations of the same repository, attached twice in one build type) is CONFLICT,
        // the same as two build types disagreeing with each other -- UNEXPRESSIBLE is reserved for
        // a rule/WORK_DIR SHAPE that can't be parsed at all (a remap, several rules, `%VAR%`).
        val gateway = "ssh://h/prj/app-two.git"
        val app = "ssh://h/prj/app-one.git"
        val bt = compileConfig(gateway to "", app to "+:. => app-one", app to "+:. => other-name")
        val outcome = derive(DeriveInput(ENTRIES, listOf(bt), 0, emptyMap()))
        assertEquals(PlacementRowStatus.CONFLICT, outcome.status)
    }

    @Test
    fun `one resolvable rule plus one unparseable rule for the same repo is still unexpressible`() {
        // Distinguishing CONFLICT from UNEXPRESSIBLE by "how many
        // DISTINCT parsed values" alone treats a null (unparseable) parse as just another distinct
        // value, so one resolvable rule + one shape the engine can't parse at all became CONFLICT.
        // A genuinely unparseable rule is a SHAPE problem regardless of what else is attached --
        // it must stay UNEXPRESSIBLE even when another attachment of the same repo is resolvable.
        val gateway = "ssh://h/prj/app-two.git"
        val app = "ssh://h/prj/app-one.git"
        val bt = compileConfig(gateway to "", app to "+:. => app-one", app to "+:a => b") // "+:a => b" is a remap: unparseable
        val outcome = derive(DeriveInput(ENTRIES, listOf(bt), 0, emptyMap()))
        assertEquals(PlacementRowStatus.UNEXPRESSIBLE, outcome.status)
    }

    @Test
    fun `the same repository attached twice with textually different but equivalent rules still resolves`() {
        // `+:mapper` and `+:mapper => mapper` both parse to the same PlacementValue (Source Path
        // "mapper", checkout root) — comparing raw rule text would flag this as a false conflict.
        val gateway = "ssh://h/prj/app-two.git"
        val app = "ssh://h/prj/app-one.git"
        // gateway gets a Checkout Directory too, so only `app` is at the checkout root — keeps
        // this fixture from also exercising the (unrelated) two-roots-at-root CRS validation rule.
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
        // The row cannot be safely applied, so it is UNEXPRESSIBLE.
        val gateway = "ssh://h/prj/app-two.git"
        val onlyGateway = compileConfig(gateway to "")
        val outcome = derive(DeriveInput(ENTRIES, listOf(onlyGateway), 0, emptyMap()))
        assertEquals(PlacementRowStatus.UNEXPRESSIBLE, outcome.status)
    }

    @Test
    fun `two roots at the checkout root resolves at the pure-engine layer`() {
        // UNEXPRESSIBLE is only for rule/WORK_DIR SHAPES that can't be
        // parsed (remaps, several rules, %VAR%). "Two roots at the checkout root" is a CRS
        // VALIDATION rule (VcsPlacementValidator.validateVcsPlacement's rootEntry check), not a
        // shape problem -- both roots parse cleanly to PlacementValue(null, null) each. The pure
        // engine now resolves it; TeamcityPlacementDiffService's own VcsPlacementValidator check
        // downgrades it to INVALID (see TeamcityPlacementDiffServiceTest).
        val gateway = "ssh://h/prj/app-two.git"
        val app = "ssh://h/prj/app-one.git"
        val c = compileConfig(gateway to "", app to "")
        val outcome = derive(DeriveInput(ENTRIES, listOf(c), 0, emptyMap()))
        assertEquals(PlacementRowStatus.RESOLVED, outcome.status)
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
    fun `every root in a CD without a build working directory resolves at the pure-engine layer`() {
        // Same reasoning as the "two roots at the checkout root" case above: "every root has a
        // Checkout Directory but WORK_DIR is the checkout root" is a CRS validation rule
        // (VcsPlacementValidator.validateBuildWorkingDirectory), not an unparseable shape.
        val gateway = "ssh://h/prj/app-two.git"
        val app = "ssh://h/prj/app-one.git"
        val c = compileConfig(gateway to "+:. => gw", app to "+:. => app")
        val outcome = derive(DeriveInput(ENTRIES, listOf(c), 0, emptyMap()))
        assertEquals(PlacementRowStatus.RESOLVED, outcome.status)
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
    @DisplayName("SYS-099: only derived statuses are scope-filtered, any other status is always reported")
    fun `SYS-099 only derived statuses are scope-filtered, any other status is always reported`() {
        assertEquals(
            setOf(
                PlacementRowStatus.RESOLVED,
                PlacementRowStatus.NO_CHAIN,
                PlacementRowStatus.OUTSIDE_TEMPLATES,
                PlacementRowStatus.COMPILE_PAUSED,
            ),
            PlacementRowStatus.values().filter { it.scopeFiltered }.toSet(),
        )
    }
}
