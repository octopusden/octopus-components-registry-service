package org.octopusden.octopus.components.registry.server.profile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.octopusden.octopus.components.registry.server.entity.ComponentArtifactMappingEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

private class Created(
    override val solution: Boolean = false,
    override val distributionExternal: Boolean = false,
    override val distributionExplicit: Boolean = false,
    private val values: Map<String, String> = emptyMap(),
) : CreatedComponent {
    override fun valueAt(path: String): String? = values[path]
}

class ProfileCreateCheckTest {
    private val catalog = ComponentProfileCatalog { designExampleProperties() }
    private val check = ProfileCreateCheck(catalog) { ProfileAvailability.Availability(usable = true, reason = null) }

    private val solution = Created(
        solution = true,
        distributionExternal = true,
        distributionExplicit = true,
        values = mapOf(
            "name" to "payments-solution",
        ),
    )
    private val internal = Created(values = mapOf("name" to "payments"))

    private fun rejection(block: () -> Unit): String = requireNotNull(assertThrows(IllegalArgumentException::class.java, block).message)

    @Test
    @DisplayName("Decision 7: a profile that is not configured is rejected with 'profile: '")
    fun unknownProfile() {
        assertTrue(rejection { check.check("nightly", internal) }.startsWith("profile: "))
    }

    @Test
    @DisplayName("Decision 6: a profile the user may not use is refused with 403 and the reason")
    fun unusableProfile() {
        val refusing = ProfileCreateCheck(catalog) { ProfileAvailability.Availability(usable = false, reason = "Not for you") }

        val error = assertThrows(ResponseStatusException::class.java) { refusing.check("regular-internal", internal) }

        assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
        assertEquals("Not for you", error.reason)
    }

    @ParameterizedTest(name = "{0} profile, stored solution={1} external={2} explicit={3} -> names {4}")
    @CsvSource(
        "solution, false, true, true, solution",
        "solution, true, false, true, external",
        "solution, true, true, false, explicit",
        "regular-internal, true, false, false, solution",
        "regular-external, false, false, false, external",
    )
    @DisplayName("Decision 7: a stored classification that differs from the profile is rejected, naming the flag")
    fun classificationDiffers(
        profile: String,
        solution: Boolean,
        external: Boolean,
        explicit: Boolean,
        flag: String,
    ) {
        val message = rejection { check.check(profile, Created(solution, external, explicit, mapOf("name" to "payments-solution"))) }

        assertTrue(message.startsWith("profile: ") && message.contains(flag), message)
    }

    @ParameterizedTest(name = "explicit={0}")
    @CsvSource("true", "false")
    @DisplayName("Decision 7: explicit: ask takes either stored value")
    fun explicitAsk(explicit: Boolean) {
        check.check(
            "regular-external",
            Created(
                distributionExternal = true,
                distributionExplicit = explicit,
                values = mapOf(
                    "name" to "payments",
                ),
            ),
        )
    }

    @Test
    @DisplayName("a value that matches every rule passes")
    fun rulesPass() {
        check.check("solution", solution)
    }

    @Test
    @DisplayName("a rule failure is rejected with the rule's path and message")
    fun ruleFails() {
        val message = rejection { check.check("solution", Created(true, true, true, mapOf("name" to "payments-dmp-bundle"))) }

        assertEquals("name: A solution key contains -solution, e.g. payments-solution.", message)
    }

    @Test
    @DisplayName("the regular profile's rule keeps solution words out of the key")
    fun regularRule() {
        val message = rejection { check.check("regular-internal", Created(values = mapOf("name" to "resolution-service"))) }

        assertTrue(message.startsWith("name: A regular component's key cannot contain solution or dmp-bundle."), message)
    }

    @Test
    @DisplayName("an absent value is matched as empty")
    fun absentValueIsEmpty() {
        val strict = ComponentProfileCatalog {
            profileProperties(
                "strict",
                rules = mapOf(
                    "clientCode" to rule("^[A-Z]+$", "Client code required."),
                ),
            )
        }

        val message =
            rejection {
                ProfileCreateCheck(
                    strict,
                ) { ProfileAvailability.Availability(true, null) }.check("strict", Created(distributionExternal = true))
            }

        assertEquals("clientCode: Client code required.", message)
    }

    @Test
    @DisplayName("Decision 8: the stored component reads every rule path, the first list entry only")
    fun entityReadsEveryPath() {
        val entity = ComponentEntity(componentKey = "payments-solution")
        entity.artifactMappings.add(
            ComponentArtifactMappingEntity(component = entity, groupPattern = "org.example.payments", sortOrder = 0),
        )
        val base = ComponentConfigurationEntity(component = entity, rowType = "BASE")
        base.vcsEntries.add(
            VcsSettingsEntryEntity(componentConfiguration = base, name = "main", vcsPath = "ssh://git@host/first.git", sortOrder = 0),
        )
        base.vcsEntries.add(
            VcsSettingsEntryEntity(componentConfiguration = base, name = "second", vcsPath = "https://host/second.git", sortOrder = 1),
        )
        val stored = EntityCreatedComponent(entity, base)

        assertEquals(CreateRequestPaths.PATHS, EntityCreatedComponent.READERS.keys)
        assertEquals("payments-solution", stored.valueAt("name"))
        assertEquals("org.example.payments", stored.valueAt("artifactIds[0].groupPattern"))
        assertEquals("ssh://git@host/first.git", stored.valueAt("baseConfiguration.vcsEntries[0].vcsPath"))
    }
}
