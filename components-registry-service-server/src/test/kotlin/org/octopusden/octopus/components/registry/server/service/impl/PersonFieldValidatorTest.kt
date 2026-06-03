package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

/**
 * Focused, framework-free unit tests for [PersonFieldValidator] — the Stage-1
 * person-field contract (required / per-element pattern / conditional gate /
 * active-employee fail-open). No Spring, no DB; a [FakeDirectory] supplies the
 * [ActiveStatus] per username and `isHidden` is a plain predicate.
 *
 * These are the RED-first guards: they exercise the rules directly so they don't
 * extend the global TestComponents/Defaults fixtures (per the regression-guard
 * rule).
 */
class PersonFieldValidatorTest {
    /**
     * Stand-in for [EmployeeDirectoryService]. We subclass it with an empty
     * [org.springframework.beans.factory.ObjectProvider] so the real
     * fail-open/exception logic is bypassed and [isActive] returns a scripted
     * status per username.
     */
    private class FakeDirectory(
        private val statuses: Map<String, ActiveStatus> = emptyMap(),
        private val default: ActiveStatus = ActiveStatus.ACTIVE,
    ) : EmployeeDirectoryService(EmptyObjectProvider()) {
        override fun isActive(username: String): ActiveStatus = statuses[username] ?: default
    }

    private val allVisible: (String) -> Boolean = { false }

    private fun validate(
        owner: String? = "owner1",
        releaseManagers: List<String> = emptyList(),
        securityChampions: List<String> = emptyList(),
        explicit: Boolean? = false,
        external: Boolean? = false,
        runActiveCheck: Boolean = false,
        isHidden: (String) -> Boolean = allVisible,
        directory: EmployeeDirectoryService = FakeDirectory(),
    ) = PersonFieldValidator.validate(
        owner = owner,
        releaseManagers = releaseManagers,
        securityChampions = securityChampions,
        explicit = explicit,
        external = external,
        runActiveCheck = runActiveCheck,
        isHidden = isHidden,
        directory = directory,
    )

    // --- required / pattern (run unconditionally, no employee bean needed) ---

    @Test
    @DisplayName("blank componentOwner is rejected with a field-prefixed message")
    fun `blank owner rejected`() {
        val ex = assertThrows<IllegalArgumentException> { validate(owner = "  ") }
        assertTrue(ex.message!!.startsWith("componentOwner"), "message must start with the field name: ${ex.message}")
    }

    @Test
    @DisplayName("non-blank componentOwner passes the required check (gate off, no active check)")
    fun `valid owner passes`() {
        assertDoesNotThrow { validate(owner = "owner1") }
    }

    @Test
    @DisplayName("RM/SC are NOT required when the distribution gate is off")
    fun `rm sc optional when gate off`() {
        assertDoesNotThrow {
            validate(explicit = false, external = true, releaseManagers = emptyList(), securityChampions = emptyList())
        }
    }

    @Test
    @DisplayName("releaseManager required under explicit && external")
    fun `rm required under gate`() {
        val ex = assertThrows<IllegalArgumentException> {
            validate(explicit = true, external = true, releaseManagers = emptyList(), securityChampions = listOf("sc1"))
        }
        assertTrue(ex.message!!.startsWith("releaseManager"), ex.message)
    }

    @Test
    @DisplayName("securityChampion required under explicit && external")
    fun `sc required under gate`() {
        val ex = assertThrows<IllegalArgumentException> {
            validate(explicit = true, external = true, releaseManagers = listOf("rm1"), securityChampions = emptyList())
        }
        assertTrue(ex.message!!.startsWith("securityChampion"), ex.message)
    }

    @Test
    @DisplayName("per-element username pattern: \"alice,bob\" is a SINGLE element that fails ^\\w+\$")
    fun `csv-like element fails pattern`() {
        val ex = assertThrows<IllegalArgumentException> {
            validate(
                explicit = true,
                external = true,
                releaseManagers = listOf("alice,bob"),
                securityChampions = listOf("sc1"),
            )
        }
        assertTrue(ex.message!!.startsWith("releaseManager"), ex.message)
        assertTrue(ex.message!!.contains("alice,bob"), ex.message)
    }

    @Test
    @DisplayName("valid per-element usernames pass under the gate")
    fun `valid list elements pass under gate`() {
        assertDoesNotThrow {
            validate(
                explicit = true,
                external = true,
                releaseManagers = listOf("rm_1", "rm2"),
                securityChampions = listOf("sc1"),
            )
        }
    }

    @Test
    @DisplayName("hidden componentOwner is skipped entirely (no required check)")
    fun `hidden owner skipped`() {
        assertDoesNotThrow {
            validate(owner = null, isHidden = { it == PersonFieldValidator.OWNER_FIELD })
        }
    }

    @Test
    @DisplayName("hidden releaseManager is skipped even under the gate")
    fun `hidden rm skipped under gate`() {
        assertDoesNotThrow {
            validate(
                explicit = true,
                external = true,
                releaseManagers = emptyList(),
                securityChampions = listOf("sc1"),
                isHidden = { it == PersonFieldValidator.RELEASE_MANAGER_FIELD },
            )
        }
    }

    // --- active-employee check (runActiveCheck = true) ---

    @Test
    @DisplayName("active owner passes when active check runs")
    fun `active owner passes`() {
        assertDoesNotThrow {
            validate(
                owner = "owner1",
                runActiveCheck = true,
                directory = FakeDirectory(mapOf("owner1" to ActiveStatus.ACTIVE)),
            )
        }
    }

    @Test
    @DisplayName("inactive owner → 400 (IllegalArgumentException), field-prefixed")
    fun `inactive owner rejected`() {
        val ex = assertThrows<IllegalArgumentException> {
            validate(
                owner = "owner1",
                runActiveCheck = true,
                directory = FakeDirectory(mapOf("owner1" to ActiveStatus.INACTIVE)),
            )
        }
        assertTrue(ex.message!!.startsWith("componentOwner"), ex.message)
    }

    @Test
    @DisplayName("unknown owner (NotFound) → 400")
    fun `unknown owner rejected`() {
        val ex = assertThrows<IllegalArgumentException> {
            validate(
                owner = "ghost",
                runActiveCheck = true,
                directory = FakeDirectory(mapOf("ghost" to ActiveStatus.UNKNOWN)),
            )
        }
        assertTrue(ex.message!!.startsWith("componentOwner"), ex.message)
    }

    @Test
    @DisplayName("UNAVAILABLE (transport error) → allow (fail-open)")
    fun `unavailable owner allowed`() {
        assertDoesNotThrow {
            validate(
                owner = "owner1",
                runActiveCheck = true,
                directory = FakeDirectory(mapOf("owner1" to ActiveStatus.UNAVAILABLE)),
            )
        }
    }

    @Test
    @DisplayName("DISABLED (no bean) → allow (fail-open), but required check still applies")
    fun `disabled owner allowed but required still enforced`() {
        // active-check disabled, but the unconditional required check still fires
        assertThrows<IllegalArgumentException> {
            validate(
                owner = null,
                runActiveCheck = true,
                directory = FakeDirectory(default = ActiveStatus.DISABLED),
            )
        }
        assertDoesNotThrow {
            validate(
                owner = "owner1",
                runActiveCheck = true,
                directory = FakeDirectory(default = ActiveStatus.DISABLED),
            )
        }
    }

    @Test
    @DisplayName("active check covers RM/SC even when gate is off")
    fun `active check on rm sc even when gate is off`() {
        // gate off → inactive RM rejected
        val exOff = assertThrows<IllegalArgumentException> {
            validate(
                owner = "owner1",
                explicit = false,
                external = true,
                releaseManagers = listOf("rmInactive"),
                runActiveCheck = true,
                directory = FakeDirectory(mapOf("rmInactive" to ActiveStatus.INACTIVE)),
            )
        }
        assertTrue(exOff.message!!.startsWith("releaseManager"), exOff.message)

        // gate on → inactive RM rejected
        val exOn = assertThrows<IllegalArgumentException> {
            validate(
                owner = "owner1",
                explicit = true,
                external = true,
                releaseManagers = listOf("rmInactive"),
                securityChampions = listOf("sc1"),
                runActiveCheck = true,
                directory = FakeDirectory(
                    mapOf(
                        "owner1" to ActiveStatus.ACTIVE,
                        "rmInactive" to ActiveStatus.INACTIVE,
                        "sc1" to ActiveStatus.ACTIVE,
                    ),
                ),
            )
        }
        assertTrue(exOn.message!!.startsWith("releaseManager"), exOn.message)
    }

    @Test
    @DisplayName("active check is NOT run when runActiveCheck=false (grandfathered values)")
    fun `no active check when not triggered`() {
        assertDoesNotThrow {
            validate(
                owner = "owner1",
                runActiveCheck = false,
                directory = FakeDirectory(mapOf("owner1" to ActiveStatus.INACTIVE)),
            )
        }
    }
}
