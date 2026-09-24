package org.octopusden.octopus.components.registry.server.mapper

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.core.dto.RepositoryType
import org.octopusden.octopus.components.registry.core.dto.VCSSettingsDTO
import org.octopusden.octopus.components.registry.core.dto.VersionControlSystemRootDTO

/**
 * ONB-001 baseline (characterization): the source/binary shape of [VersionControlSystemRootDTO] that
 * consumers compile against.
 *
 * Pinned: the positional 6-argument constructor (Groovy consumers call it with `new ...(6 args)`), the
 * `componentN` order (a consumer destructures `(name, path)`), and tolerant deserialization of a field
 * the client does not know (`ignoreUnknown = true`), which is what lets an older client read a newer
 * server's response.
 */
class VersionControlSystemRootDtoBaselineTest {
    // Named arguments, so positional use below is checked against the declared parameter order.
    private val dto =
        VersionControlSystemRootDTO(
            name = "main",
            vcsPath = "ssh://git@example.test/proj/repo-a.git",
            type = RepositoryType.GIT,
            tag = "test-component-a-1.0",
            branch = "master",
            hotfixBranch = "hotfix/1.0",
        )

    @Test
    @DisplayName("ONB-001 baseline: DTO has a public positional 6-argument constructor in field order")
    fun `baseline dto six argument constructor`() {
        val ctor =
            VersionControlSystemRootDTO::class.java.getConstructor(
                String::class.java,
                String::class.java,
                RepositoryType::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
            )
        assertEquals(
            dto,
            ctor.newInstance(
                "main",
                "ssh://git@example.test/proj/repo-a.git",
                RepositoryType.GIT,
                "test-component-a-1.0",
                "master",
                "hotfix/1.0",
            ),
        )
    }

    @Test
    @DisplayName("ONB-001 baseline: DTO destructures as (name, vcsPath, type, tag, branch, hotfixBranch)")
    fun `baseline dto destructuring order`() {
        // The consumer's own form; the remaining positions are read through componentN directly.
        val (name, path) = dto
        assertEquals(
            listOf<Any?>(
                "main",
                "ssh://git@example.test/proj/repo-a.git",
                RepositoryType.GIT,
                "test-component-a-1.0",
                "master",
                "hotfix/1.0",
            ),
            listOf(name, path, dto.component3(), dto.component4(), dto.component5(), dto.component6()),
        )
    }

    @Test
    @DisplayName("ONB-001 baseline: DTO deserialization ignores an unknown field")
    fun `baseline dto ignores unknown field on deserialization`() {
        val json =
            """{"versionControlSystemRoots":[{"name":"main","vcsPath":"ssh://git@example.test/proj/repo-a.git",""" +
                """"type":"GIT","tag":"test-component-a-1.0","branch":"master","hotfixBranch":"hotfix/1.0",""" +
                """"someFutureField":"value"}]}"""

        val parsed: VCSSettingsDTO = jacksonObjectMapper().readValue(json)

        assertEquals(VCSSettingsDTO(listOf(dto)), parsed)
    }

    @Test
    @DisplayName("ONB-001: placement properties are appended after the six existing ones and omitted from JSON when null")
    fun `placement properties appended`() {
        val placed = dto.copy(sourcePath = "data", checkoutDirectory = "feature")
        val (name, path) = placed

        assertEquals(listOf("main", "ssh://git@example.test/proj/repo-a.git"), listOf(name, path))
        assertEquals(listOf("data", "feature"), listOf(placed.component7(), placed.component8()))
        assertEquals(
            """{"name":"main","vcsPath":"ssh://git@example.test/proj/repo-a.git","type":"GIT",""" +
                """"tag":"test-component-a-1.0","branch":"master","hotfixBranch":"hotfix/1.0"}""",
            jacksonObjectMapper().writeValueAsString(dto),
        )
    }
}
