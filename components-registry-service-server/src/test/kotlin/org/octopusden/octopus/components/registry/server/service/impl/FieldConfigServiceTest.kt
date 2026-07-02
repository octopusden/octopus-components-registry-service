package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.octopusden.octopus.components.registry.server.entity.RegistryConfigEntity
import org.octopusden.octopus.components.registry.server.repository.RegistryConfigRepository
import java.util.Optional
import org.mockito.Mockito.`when` as whenMock

/**
 * Risk-1 mitigation — `FieldConfigService` resolves visibility for a
 * section-prefixed path against `registry_config[field-config].value`,
 * with several "missing" branches that all fall back to "editable" so
 * the service can land without a config-data migration.
 */
class FieldConfigServiceTest {
    private fun service(stored: Map<String, Any?>?): FieldConfigService {
        val repo = mock(RegistryConfigRepository::class.java)
        val optional =
            stored?.let { Optional.of(RegistryConfigEntity(key = "field-config", value = it)) }
                ?: Optional.empty()
        whenMock(repo.findById("field-config")).thenReturn(optional)
        return FieldConfigService(repo)
    }

    @Test
    @DisplayName("field-config row absent → all paths resolve to editable")
    fun rowAbsent_allEditable() {
        val svc = service(stored = null)

        assertEquals("editable", svc.visibilityFor("component.displayName"))
        assertFalse(svc.isHidden("component.displayName"))
    }

    @Test
    @DisplayName("section-prefixed path with hidden visibility → isHidden=true")
    fun sectioned_hidden() {
        val svc =
            service(
                mapOf(
                    "component" to
                        mapOf(
                            "displayName" to mapOf("visibility" to "hidden"),
                        ),
                ),
            )

        assertTrue(svc.isHidden("component.displayName"))
        assertEquals("hidden", svc.visibilityFor("component.displayName"))
    }

    @Test
    @DisplayName("readonly field → visibility returned, isHidden=false, editabilityFor unifies to none")
    fun readonly_notHidden() {
        val svc =
            service(
                mapOf(
                    "component" to
                        mapOf(
                            "displayName" to mapOf("visibility" to "readonly"),
                        ),
                ),
            )

        assertEquals("readonly", svc.visibilityFor("component.displayName"))
        assertFalse(svc.isHidden("component.displayName"))
        // CRS-B: readonly is unified with editable:none (non-editable for everyone).
        assertEquals("none", svc.editabilityFor("component.displayName"))
    }

    @Test
    @DisplayName("editabilityFor: unconfigured / absent-editable → all")
    fun editability_defaultsToAll() {
        assertEquals("all", service(stored = null).editabilityFor("jira.technical"))
        assertEquals(
            "all",
            service(mapOf("jira" to mapOf("technical" to mapOf("visibility" to "editable"))))
                .editabilityFor("jira.technical"),
        )
    }

    @Test
    @DisplayName("editabilityFor: explicit adminOnly / none normalized from mixed case + whitespace")
    fun editability_adminOnlyAndNone() {
        val svc =
            service(
                mapOf(
                    "jira" to mapOf("technical" to mapOf("editable" to "  AdminOnly ")),
                    "component" to mapOf("system" to mapOf("editable" to "NONE")),
                ),
            )
        assertEquals("adminonly", svc.editabilityFor("jira.technical"))
        assertEquals("none", svc.editabilityFor("component.system"))
    }

    @Test
    @DisplayName("editabilityFor: explicit editable:none overrides even when visibility=editable")
    fun editability_noneWithEditableVisibility() {
        val svc =
            service(
                mapOf("jira" to mapOf("technical" to mapOf("visibility" to "editable", "editable" to "none"))),
            )
        assertEquals("none", svc.editabilityFor("jira.technical"))
    }

    @Test
    @DisplayName("editabilityFor: hidden field (no editable key) still resolves to all — strip is a separate axis")
    fun editability_hiddenResolvesToAll() {
        val svc =
            service(
                mapOf("component" to mapOf("displayName" to mapOf("visibility" to "hidden"))),
            )
        // Hidden is enforced via isHidden (silent strip), not via editabilityFor.
        assertTrue(svc.isHidden("component.displayName"))
        assertEquals("all", svc.editabilityFor("component.displayName"))
    }

    @Test
    @DisplayName("editabilityFor: unrecognized editable token degrades to all")
    fun editability_unknownTokenAll() {
        val svc =
            service(
                mapOf("jira" to mapOf("technical" to mapOf("editable" to "mostly"))),
            )
        assertEquals("all", svc.editabilityFor("jira.technical"))
    }

    @Test
    @DisplayName("section absent → editable fallback")
    fun sectionAbsent_editable() {
        val svc = service(mapOf("build" to emptyMap<String, Any?>()))

        assertEquals("editable", svc.visibilityFor("component.displayName"))
    }

    @Test
    @DisplayName("field absent within section → editable fallback")
    fun fieldAbsent_editable() {
        val svc =
            service(
                mapOf(
                    "component" to mapOf("componentOwner" to mapOf("visibility" to "hidden")),
                ),
            )

        assertEquals("editable", svc.visibilityFor("component.displayName"))
    }

    @Test
    @DisplayName("malformed entry (visibility not a string) → editable fallback")
    fun malformed_editable() {
        val svc =
            service(
                mapOf(
                    "component" to mapOf("displayName" to mapOf("visibility" to 42)),
                ),
            )

        assertEquals("editable", svc.visibilityFor("component.displayName"))
    }

    @Test
    @DisplayName("path without dot → editable fallback (silently — not an exception)")
    fun bareFieldPath_editable() {
        val svc =
            service(
                mapOf(
                    "component" to mapOf("displayName" to mapOf("visibility" to "hidden")),
                ),
            )

        // Bare paths are not the section-prefixed convention; the service
        // intentionally does NOT scan sections to find them. Callers must
        // pass the full "section.field" form.
        assertEquals("editable", svc.visibilityFor("displayName"))
    }

    @Test
    @DisplayName("visibility with surrounding whitespace and mixed case → trimmed + lowercased")
    fun whitespaceAndCase_normalized() {
        val svc =
            service(
                mapOf(
                    "component" to mapOf("displayName" to mapOf("visibility" to "  Hidden  ")),
                ),
            )

        assertTrue(svc.isHidden("component.displayName"))
        assertEquals("hidden", svc.visibilityFor("component.displayName"))
    }

    @Test
    @DisplayName("blank visibility string → editable fallback")
    fun blankVisibility_editable() {
        val svc =
            service(
                mapOf(
                    "component" to mapOf("displayName" to mapOf("visibility" to "")),
                ),
            )

        assertEquals("editable", svc.visibilityFor("component.displayName"))
    }
}
