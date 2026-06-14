package org.octopusden.octopus.components.registry.cli.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ComponentDetailResponseTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesDetailWithNestedConfiguration() {
        val literal = """
            {
              "id": "22222222-2222-2222-2222-222222222222",
              "name": "beta",
              "displayName": "Beta",
              "archived": false,
              "canBeParent": false,
              "version": 7,
              "canEdit": true,
              "componentOwner": "owner1",
              "labels": ["lib"],
              "artifactIds": [
                { "id": "a1", "artifactPattern": "art-*", "groupPattern": "grp" }
              ],
              "configurations": [
                {
                  "id": "c1",
                  "isSyntheticBase": true,
                  "rowType": "BASE",
                  "versionRange": "[1.0,)",
                  "requiredTools": ["jdk"],
                  "buildToolBeans": [],
                  "dockerImages": [],
                  "fileUrlArtifacts": [],
                  "mavenArtifacts": [
                    { "id": "m1", "artifactPattern": "p", "groupPattern": "g", "sortOrder": 0 }
                  ],
                  "packages": [],
                  "vcsEntries": [
                    { "id": "v1", "sortOrder": 0, "vcsPath": "ssh://repo", "branch": "main" }
                  ],
                  "build": { "buildSystem": "GRADLE", "javaVersion": "21" },
                  "jira": { "projectKey": "BETA", "technical": false }
                }
              ],
              "docs": [],
              "releaseManager": ["rm1"],
              "securityChampion": [],
              "securityGroups": [
                { "id": "s1", "groupName": "g", "groupType": "read" }
              ],
              "teamcityProjects": [
                { "id": "t1", "projectId": "Tc_Beta", "sortOrder": 0 }
              ],
              "group": { "groupKey": "GK", "isFake": false, "role": "MEMBER" },
              "createdAt": "2026-06-13T10:00:00Z",
              "updatedAt": "2026-06-13T11:00:00Z"
            }
        """.trimIndent()

        val detail = json.decodeFromString<ComponentDetailResponse>(literal)

        assertEquals("22222222-2222-2222-2222-222222222222", detail.id)
        assertEquals("beta", detail.name)
        assertEquals(7L, detail.version)
        assertEquals(true, detail.canEdit)
        assertEquals("owner1", detail.componentOwner)
        assertEquals(listOf("lib"), detail.labels)
        assertEquals(1, detail.artifactIds.size)
        assertEquals("art-*", detail.artifactIds.first().artifactPattern)
        assertEquals(1, detail.configurations.size)

        val cfg = detail.configurations.first()
        assertEquals("BASE", cfg.rowType)
        assertEquals("[1.0,)", cfg.versionRange)
        assertEquals(true, cfg.isSyntheticBase)
        assertEquals(listOf("jdk"), cfg.requiredTools)
        assertEquals(1, cfg.mavenArtifacts.size)
        assertEquals("GRADLE", cfg.build?.buildSystem)
        assertEquals("21", cfg.build?.javaVersion)
        assertEquals("BETA", cfg.jira?.projectKey)
        assertEquals("main", cfg.vcsEntries.first().branch)

        assertEquals("MEMBER", detail.group?.role)
        assertEquals("Tc_Beta", detail.teamcityProjects.first().projectId)
    }
}
