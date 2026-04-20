package org.octopusden.octopus.components.registry.server.service.impl

import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import java.io.File
import java.nio.file.Path

class GitTagResolverTest {
    private val resolver =
        GitTagResolver(
            NumericVersionFactory(
                VersionNames("serviceBranch", "service", "minor"),
            ),
        )

    @Test
    fun `picks highest numeric tag with configured prefix`(
        @TempDir tmp: Path,
    ) {
        Git.init().setDirectory(tmp.toFile()).call().use { git ->
            val c1 = commit(git, tmp, "a", "1")
            tag(git, "components-registry-1.1")
            val c2 = commit(git, tmp, "a", "2")
            tag(git, "components-registry-1.2")
            commit(git, tmp, "a", "3")
            tag(git, "escrow-config-1.10")

            val target = resolver.resolve(git, "refs/tags/components-registry-")

            assertEquals("refs/tags/components-registry-1.2", target.ref)
            assertEquals(c2, target.sha)
            assertTrue(c1.isNotEmpty())
        }
    }

    @Test
    fun `ignores non-numeric tag with matching prefix`(
        @TempDir tmp: Path,
    ) {
        Git.init().setDirectory(tmp.toFile()).call().use { git ->
            commit(git, tmp, "a", "1")
            tag(git, "components-registry-1.1")
            commit(git, tmp, "a", "2")
            tag(git, "components-registry-qa-1") // non-numeric suffix — must be skipped

            val target = resolver.resolve(git, "refs/tags/components-registry-")

            assertEquals("refs/tags/components-registry-1.1", target.ref)
        }
    }

    @Test
    fun `falls back to master HEAD when no tags match prefix`(
        @TempDir tmp: Path,
    ) {
        Git.init().setDirectory(tmp.toFile()).call().use { git ->
            commit(git, tmp, "a", "1")
            val head = commit(git, tmp, "a", "2")
            tag(git, "escrow-config-1.10") // wrong prefix

            val target = resolver.resolve(git, "refs/tags/components-registry-")

            assertEquals("master", target.ref)
            assertEquals(head, target.sha)
        }
    }

    @Test
    fun `empty repository throws IllegalStateException`(
        @TempDir tmp: Path,
    ) {
        Git.init().setDirectory(tmp.toFile()).call().use { git ->
            assertThrows(IllegalStateException::class.java) {
                resolver.resolve(git, "refs/tags/components-registry-")
            }
        }
    }

    private fun commit(
        git: Git,
        tmp: Path,
        file: String,
        content: String,
    ): String {
        File(tmp.toFile(), file).writeText(content)
        git.add().addFilepattern(file).call()
        return git
            .commit()
            .setMessage("$file=$content")
            .setAuthor("Tester", "test@example.com")
            .call()
            .name
    }

    private fun tag(
        git: Git,
        name: String,
    ) {
        git.tag().setName(name).call()
    }
}
