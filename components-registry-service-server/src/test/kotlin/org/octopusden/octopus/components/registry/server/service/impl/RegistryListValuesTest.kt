package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.model.TemplateList

class RegistryListValuesTest {
    @Test
    @DisplayName("Decision 5: labels are read from the dictionary on every call")
    fun labelsFromDictionary() {
        var dictionary = listOf("plugin")
        val lists = RegistryListValues { dictionary }

        assertEquals(setOf("plugin"), lists.values(TemplateList.LABELS))
        dictionary = listOf("plugin", "ui")
        assertEquals(setOf("plugin", "ui"), lists.values(TemplateList.LABELS))
    }

    @Test
    @DisplayName("Decision 5: build systems and escrow generation modes come from their enums, as /meta serves them")
    fun enums() {
        val lists = RegistryListValues { error("the dictionary is read for labels only") }

        assertTrue("GRADLE" in lists.values(TemplateList.BUILD_SYSTEMS))
        assertTrue("UNSUPPORTED" in lists.values(TemplateList.ESCROW_GENERATION))
    }
}
