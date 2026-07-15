package org.octopusden.octopus.components.registry.cli.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AuditLogResponseTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesAuditPageWithFreeFormDiff() {
        val literal =
            """
            {
              "content": [
                {
                  "id": 42,
                  "action": "UPDATE",
                  "entityType": "component",
                  "entityId": "33333333-3333-3333-3333-333333333333",
                  "changedAt": "2026-06-13T09:30:00Z",
                  "changedBy": "user1",
                  "source": "PORTAL",
                  "correlationId": "corr-1",
                  "changeDiff": { "displayName": "renamed" },
                  "oldValue": { "displayName": "old" },
                  "newValue": { "displayName": "new" }
                }
              ],
              "number": 0,
              "size": 50,
              "totalElements": 1,
              "totalPages": 1,
              "last": true
            }
            """.trimIndent()

        val page = json.decodeFromString<PageAuditLogResponse>(literal)

        assertEquals(1, page.totalElements)
        assertEquals(1, page.content?.size)

        val row = page.content!!.first()
        assertEquals(42L, row.id)
        assertEquals("UPDATE", row.action)
        assertEquals("component", row.entityType)
        assertEquals("33333333-3333-3333-3333-333333333333", row.entityId)
        assertEquals("user1", row.changedBy)
        assertEquals("PORTAL", row.source)

        val diff = row.changeDiff
        assertNotNull(diff)
        assertEquals("renamed", diff["displayName"]?.jsonPrimitive?.content)
        assertEquals(
            "new",
            row.newValue
                ?.get("displayName")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            "old",
            row.oldValue
                ?.get("displayName")
                ?.jsonPrimitive
                ?.content,
        )
    }
}
