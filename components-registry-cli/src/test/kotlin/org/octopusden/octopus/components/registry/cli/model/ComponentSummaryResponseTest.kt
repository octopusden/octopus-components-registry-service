package org.octopusden.octopus.components.registry.cli.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComponentSummaryResponseTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesPageWithSummaryRows() {
        val literal = """
            {
              "content": [
                {
                  "id": "11111111-1111-1111-1111-111111111111",
                  "name": "alpha",
                  "displayName": "Alpha",
                  "archived": false,
                  "canBeParent": true,
                  "labels": ["core", "public"],
                  "system": "SYS",
                  "buildSystem": "GRADLE",
                  "futureUnknownKey": "ignored"
                }
              ],
              "empty": false,
              "first": true,
              "last": true,
              "number": 0,
              "numberOfElements": 1,
              "size": 20,
              "totalElements": 1,
              "totalPages": 1,
              "pageable": { "pageNumber": 0, "pageSize": 20, "paged": true },
              "sort": { "empty": true, "sorted": false, "unsorted": true }
            }
        """.trimIndent()

        val page = json.decodeFromString<PageComponentSummaryResponse>(literal)

        assertEquals(1, page.totalElements)
        assertEquals(1, page.totalPages)
        assertEquals(0, page.number)
        assertEquals(20, page.size)
        assertEquals(true, page.last)
        assertEquals(1, page.content?.size)

        val row = page.content!!.first()
        assertEquals("11111111-1111-1111-1111-111111111111", row.id)
        assertEquals("alpha", row.name)
        assertEquals("Alpha", row.displayName)
        assertEquals(false, row.archived)
        assertEquals(true, row.canBeParent)
        assertEquals(listOf("core", "public"), row.labels)
        assertEquals("GRADLE", row.buildSystem)
        assertTrue(page.pageable?.paged == true)
    }
}
