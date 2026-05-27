package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths
import java.util.UUID

/**
 * Strict-contract validation introduced by the UI-swift-sloth iteration.
 *
 * The Portal's Create Component flow now requires `group` and
 * `baseConfiguration.build.buildSystem` (frontend UX). The server is the
 * source of truth: it MUST reject create payloads missing either field with
 * 400 so a misbehaving client cannot land an under-specified component.
 *
 * On PATCH, semantics differ:
 *  - `group: null` continues to mean "don't touch" (Jackson cannot
 *    distinguish field-absent from explicit-null without a presence-preserving
 *    DTO, and every untouched-group PATCH today carries `group == null`).
 *  - `clearGroup: true` becomes invalid because there is no path for a
 *    component to legitimately exist without a group. Service rejects with 400.
 *  - `clearGroup: false` (explicit) with no `group` key is the canonical
 *    no-op shape the Portal sends when the group field is untouched —
 *    must remain 2xx and not modify the existing group.
 *
 * `baseConfiguration.build.buildSystem` may be omitted from PATCH (PATCH
 * cannot blank an already-set scalar via "field absent"; the BASE row is
 * already valid). Only CREATE is gated.
 *
 * All test fixtures use RFC 2606 reserved-for-examples placeholders
 * (`com.example.*`) — no real customer / product domain in source.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
class StrictContractTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(StrictContractTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun unique(prefix: String) = "${prefix}_${UUID.randomUUID().toString().take(8)}"

    private fun postCreate(body: String) =
        mvc.perform(
            post("/rest/api/4/components")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )

    private fun validCreateBody(name: String, groupKey: String = "org.example.test") =
        """
        {
          "name": "$name",
          "group": {"groupKey": "$groupKey", "isFake": false},
          "baseConfiguration": {"build": {"buildSystem": "MAVEN"}}
        }
        """.trimIndent()

    @Test
    @DisplayName("CREATE rejects payload missing 'group' (400 with group-required hint)")
    fun create_rejects_missingGroup() {
        val body =
            """
            {
              "name": "${unique("strict-no-group")}",
              "baseConfiguration": {"build": {"buildSystem": "MAVEN"}}
            }
            """.trimIndent()
        postCreate(body).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("CREATE rejects payload missing 'baseConfiguration' entirely")
    fun create_rejects_missingBaseConfiguration() {
        val body =
            """
            {
              "name": "${unique("strict-no-bc")}",
              "group": {"groupKey": "org.example.test", "isFake": false}
            }
            """.trimIndent()
        postCreate(body).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("CREATE rejects payload missing 'baseConfiguration.build.buildSystem'")
    fun create_rejects_missingBuildSystem() {
        val body =
            """
            {
              "name": "${unique("strict-no-bs")}",
              "group": {"groupKey": "org.example.test", "isFake": false},
              "baseConfiguration": {"build": {}}
            }
            """.trimIndent()
        postCreate(body).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("CREATE rejects 'baseConfiguration.build' absent (no build block)")
    fun create_rejects_missingBuildBlock() {
        val body =
            """
            {
              "name": "${unique("strict-no-build")}",
              "group": {"groupKey": "org.example.test", "isFake": false},
              "baseConfiguration": {}
            }
            """.trimIndent()
        postCreate(body).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("CREATE rejects 'group' present but groupKey blank")
    fun create_rejects_blankGroupKey() {
        val body =
            """
            {
              "name": "${unique("strict-blank-grp")}",
              "group": {"groupKey": "  ", "isFake": false},
              "baseConfiguration": {"build": {"buildSystem": "MAVEN"}}
            }
            """.trimIndent()
        postCreate(body).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("CREATE accepts payload with required fields present (2xx)")
    fun create_accepts_fullyValidPayload() {
        val body = validCreateBody(unique("strict-valid"))
        postCreate(body).andExpect(status().is2xxSuccessful)
    }

    @Test
    @DisplayName("PATCH with {clearGroup: true} returns 400")
    fun patch_rejects_clearGroupTrue() {
        // Seed a component with the required fields.
        val name = unique("strict-patch-clear")
        val seedResponse =
            postCreate(validCreateBody(name)).andExpect(status().isCreated)
                .andReturn().response.contentAsString
        val seed = objectMapper.readTree(seedResponse)
        val id = seed["id"].asText()
        val versionLock = seed["version"].asLong()

        val patchBody = """{"version": $versionLock, "clearGroup": true}"""
        mvc.perform(
            patch("/rest/api/4/components/$id")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(patchBody),
        ).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("PATCH with {clearGroup: false, version: N} (no group key) is a no-op and preserves the existing group")
    fun patch_noOp_clearGroupFalse_preservesGroup() {
        // The Portal sends {clearGroup: false, version: N, ...} on every save where
        // the group field was not touched (see buildUpdateRequest.ts). This shape
        // must remain a 2xx that does NOT modify the existing group.
        val groupKey = "org.example.preserve.${UUID.randomUUID().toString().take(6)}"
        val name = unique("strict-patch-noop")
        val seedResponse =
            postCreate(validCreateBody(name, groupKey = groupKey)).andExpect(status().isCreated)
                .andReturn().response.contentAsString
        val seed = objectMapper.readTree(seedResponse)
        val id = seed["id"].asText()
        val versionLock = seed["version"].asLong()

        val patchBody = """{"version": $versionLock, "clearGroup": false}"""
        val patched =
            mvc.perform(
                patch("/rest/api/4/components/$id")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(patchBody),
            ).andExpect(status().isOk)
                .andExpect(jsonPath("$.group.groupKey").value(groupKey))
                .andReturn().response.contentAsString
        // Sanity: the response still carries the original group.
        val groupAfter = objectMapper.readTree(patched)["group"]
        assert(groupAfter != null && groupAfter["groupKey"].asText() == groupKey) {
            "expected group preserved with groupKey='$groupKey'; got $groupAfter"
        }
    }

    @Test
    @DisplayName("PATCH with new {group: {...}} updates the group (2xx)")
    fun patch_accepts_newGroup() {
        val name = unique("strict-patch-newgrp")
        val seedResponse =
            postCreate(validCreateBody(name)).andExpect(status().isCreated)
                .andReturn().response.contentAsString
        val seed = objectMapper.readTree(seedResponse)
        val id = seed["id"].asText()
        val versionLock = seed["version"].asLong()

        val newKey = "org.example.other.${UUID.randomUUID().toString().take(6)}"
        val patchBody =
            """{"version": $versionLock, "clearGroup": false, "group": {"groupKey": "$newKey", "isFake": false}}"""
        mvc.perform(
            patch("/rest/api/4/components/$id")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(patchBody),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.group.groupKey").value(newKey))
    }
}
