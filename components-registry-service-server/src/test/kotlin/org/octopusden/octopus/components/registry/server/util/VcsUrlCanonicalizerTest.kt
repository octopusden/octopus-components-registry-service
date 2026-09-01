package org.octopusden.octopus.components.registry.server.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class VcsUrlCanonicalizerTest {

    @ParameterizedTest(name = "{0} and {1} canonicalize to the same value")
    @CsvSource(
        "ssh://git.example.com/repo.git, https://git.example.com/repo",
        "ssh://git.example.com/repo.git, ssh://git.example.com/repo",
        "ssh://git.example.com/Repo.git, ssh://GIT.EXAMPLE.COM/repo.git",
        "ssh://git.example.com/repo.git/, ssh://git.example.com/repo.git",
        "https://git.example.com/repo.git/, https://git.example.com/repo",
        "git@git.example.com:owner/repo.git, ssh://git.example.com/owner/repo",
        "git@git.example.com:owner/repo.git, https://git.example.com/owner/repo",
        "https://git.example.com/repo.GIT, https://git.example.com/repo",
        "https://git.example.com/repo.GIT, https://git.example.com/repo.git",
        "http://git.example.com/repo.git, https://git.example.com/repo",
        "git://git.example.com/repo.git, https://git.example.com/repo",
    )
    fun `equivalent URLs canonicalize to the same value`(a: String, b: String) {
        assertThat(VcsUrlCanonicalizer.canonicalize(a))
            .isEqualTo(VcsUrlCanonicalizer.canonicalize(b))
    }

    @Test
    fun `different repos canonicalize to different values`() {
        val a = VcsUrlCanonicalizer.canonicalize("ssh://git.example.com/repo-a.git")
        val b = VcsUrlCanonicalizer.canonicalize("ssh://git.example.com/repo-b.git")
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `scp-style URL is not confused with an explicit scheme plus port`() {
        val scpStyle = VcsUrlCanonicalizer.canonicalize("git@git.example.com:owner/repo.git")
        val explicitWithPort = VcsUrlCanonicalizer.canonicalize("ssh://git.example.com:2222/owner/repo")
        assertThat(scpStyle).isNotEqualTo(explicitWithPort)
    }

    @Test
    fun `explicit scheme with port number canonicalizes without misparsing the port as scp-style separator`() {
        val a = VcsUrlCanonicalizer.canonicalize("ssh://git.example.com:2222/owner/repo")
        val b = VcsUrlCanonicalizer.canonicalize("ssh://git.example.com:2222/owner/repo.git")
        assertThat(a).isEqualTo(b)
        assertThat(a).isEqualTo("git.example.com:2222/owner/repo")
    }
}
