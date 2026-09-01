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
}
