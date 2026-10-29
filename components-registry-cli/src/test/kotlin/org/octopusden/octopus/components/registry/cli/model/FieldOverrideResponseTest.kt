package org.octopusden.octopus.components.registry.cli.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FieldOverrideResponseTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesFieldOverrideArray() {
        val literal =
            """
            [
              {
                "id": "44444444-4444-4444-4444-444444444444",
                "overriddenAttribute": "build.javaVersion",
                "rowType": "SCALAR_OVERRIDE",
                "versionRange": "[2.0,3.0)",
                "value": { "javaVersion": "17" },
                "createdAt": "2026-06-13T08:00:00Z",
                "updatedAt": "2026-06-13T08:05:00Z"
              },
              {
                "id": "55555555-5555-5555-5555-555555555555",
                "overriddenAttribute": "marker",
                "rowType": "MARKER",
                "versionRange": "[3.0,)",
                "markerChildren": {
                  "requiredTools": ["jdk", "maven"],
                  "mavenArtifacts": [
                    { "artifactPattern": "a", "groupPattern": "g" }
                  ]
                }
              }
            ]
            """.trimIndent()

        val overrides = json.decodeFromString<List<FieldOverrideResponse>>(literal)

        assertEquals(2, overrides.size)

        val scalar = overrides[0]
        assertEquals("build.javaVersion", scalar.overriddenAttribute)
        assertEquals("SCALAR_OVERRIDE", scalar.rowType)
        assertEquals("[2.0,3.0)", scalar.versionRange)
        assertEquals(
            "17",
            scalar.value
                ?.jsonObject
                ?.get("javaVersion")
                ?.jsonPrimitive
                ?.content,
        )

        val marker = overrides[1]
        assertEquals("MARKER", marker.rowType)
        val children = marker.markerChildren
        assertNotNull(children)
        assertEquals(listOf("jdk", "maven"), children.requiredTools)
        assertEquals("a", children.mavenArtifacts?.first()?.artifactPattern)
    }
}
