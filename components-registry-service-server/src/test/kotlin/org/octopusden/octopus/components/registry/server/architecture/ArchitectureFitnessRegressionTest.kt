package org.octopusden.octopus.components.registry.server.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.architecture.fixtures.ComposedMappingProbe
import org.octopusden.octopus.components.registry.server.architecture.fixtures.ComposedV4Get
import org.octopusden.octopus.components.registry.server.architecture.fixtures.GuardedRest4ProbeEndpoint
import org.octopusden.octopus.components.registry.server.architecture.fixtures.NonV4ProbeEndpoint
import org.octopusden.octopus.components.registry.server.architecture.fixtures.Rest4ProbeEndpoint
import org.octopusden.octopus.components.registry.server.architecture.fixtures.db.DbCleanProbe
import org.octopusden.octopus.components.registry.server.architecture.fixtures.db.DbLegacyGroovyProbe
import org.octopusden.octopus.escrow.model.Distribution

/**
 * Boundary regression guards for the two [ArchitectureFitnessTest] rules whose selectors were
 * hardened after review. Each rule is evaluated (unfrozen) against a hand-built fixture set so the
 * exact bypass a reviewer found is pinned RED-without-the-fix / GREEN-with-it, independent of the
 * production violation baseline.
 *
 * - v4 authorization: scoping by request PATH, not by the `*ControllerV4` class-name convention.
 * - db→Groovy: denying by legacy PACKAGE boundary, not only by a `*Groovy*` class name.
 */
class ArchitectureFitnessRegressionTest {
    private val importer = ClassFileImporter()

    @Test
    fun `v4 rule flags an unguarded rest_api_4 endpoint not named ControllerV4`() {
        val classes = importer.importClasses(Rest4ProbeEndpoint::class.java)
        val result = ArchitectureFitnessTest.v4AuthorizationRule().evaluate(classes)
        assertTrue(
            result.hasViolation(),
            "path-scoped v4 rule must catch an unguarded rest/api/4 endpoint regardless of class name",
        )
        assertTrue(result.failureReport.toString().contains("Rest4ProbeEndpoint"))
    }

    @Test
    fun `v4 rule accepts a guarded rest_api_4 endpoint`() {
        val classes = importer.importClasses(GuardedRest4ProbeEndpoint::class.java)
        val result = ArchitectureFitnessTest.v4AuthorizationRule().evaluate(classes)
        assertFalse(result.hasViolation(), "a @PreAuthorize-guarded v4 endpoint must satisfy the rule")
    }

    @Test
    fun `v4 rule flags an unguarded endpoint declared via a composed mapping annotation`() {
        val classes = importer.importClasses(ComposedMappingProbe::class.java, ComposedV4Get::class.java)
        val result = ArchitectureFitnessTest.v4AuthorizationRule().evaluate(classes)
        assertTrue(
            result.hasViolation(),
            "endpoint detection must follow @RequestMapping meta-annotations, not just the six standard types",
        )
        assertTrue(result.failureReport.toString().contains("ComposedMappingProbe"))
    }

    @Test
    fun `v4 rule ignores endpoints outside rest_api_4`() {
        // Pair the out-of-scope (unguarded) endpoint with an in-scope guarded one so the rule
        // matches >= 1 method (avoiding ArchUnit's "checked no classes" empty-failure); a clean
        // result then proves the non-v4 unguarded endpoint was NOT pulled into the v4 gate.
        val classes = importer.importClasses(NonV4ProbeEndpoint::class.java, GuardedRest4ProbeEndpoint::class.java)
        val result = ArchitectureFitnessTest.v4AuthorizationRule().evaluate(classes)
        assertFalse(result.hasViolation(), "endpoints outside rest/api/4 are out of scope for the v4 gate")
    }

    @Test
    fun `groovy rule flags a db class depending on a groovy-authored legacy class`() {
        val classes = importer.importClasses(DbLegacyGroovyProbe::class.java, Distribution::class.java)
        val result = ArchitectureFitnessTest.dbNoLegacyGroovyRule().evaluate(classes)
        assertTrue(
            result.hasViolation(),
            "rule must deny by legacy package even when the dependency's class name has no 'Groovy' token",
        )
        assertTrue(result.failureReport.toString().contains("Distribution"))
    }

    @Test
    fun `groovy rule accepts a clean db class`() {
        val classes = importer.importClasses(DbCleanProbe::class.java)
        val result = ArchitectureFitnessTest.dbNoLegacyGroovyRule().evaluate(classes)
        assertFalse(result.hasViolation(), "a db class with no legacy dependency must satisfy the rule")
    }
}
