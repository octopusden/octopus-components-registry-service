package org.octopusden.octopus.components.registry.cli.config

import org.octopusden.octopus.components.registry.cli.client.ConfigResolutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TargetResolverTest {
    private val config = CrsctlConfig(
        defaultProfile = "qa",
        profiles = mapOf(
            "qa" to Profile(crsUrl = "https://qa.crs", clientId = "crsctl"),
            "prod" to Profile(crsUrl = "https://prod.crs"),
        ),
    )

    @Test
    fun `flag beats env var beats profile`() {
        val target = TargetResolver.resolve(
            ResolverInputs(
                crsUrlFlag = "https://flag.crs",
                crsUrlEnv = "https://env.crs",
                envFlag = "prod",
            ),
            config,
        )
        assertEquals("https://flag.crs", target.crsUrl)
        assertNull(target.envName)
    }

    @Test
    fun `env var beats profile`() {
        val target = TargetResolver.resolve(
            ResolverInputs(crsUrlEnv = "https://env.crs", envFlag = "prod"),
            config,
        )
        assertEquals("https://env.crs", target.crsUrl)
        assertNull(target.envName)
    }

    @Test
    fun `named profile used when no flag or env`() {
        val target = TargetResolver.resolve(ResolverInputs(envFlag = "prod"), config)
        assertEquals("https://prod.crs", target.crsUrl)
        assertEquals("prod", target.envName)
    }

    @Test
    fun `default profile used when nothing else given`() {
        val target = TargetResolver.resolve(ResolverInputs(), config)
        assertEquals("https://qa.crs", target.crsUrl)
        assertEquals("qa", target.envName)
    }

    @Test
    fun `token precedence flag beats env`() {
        val target = TargetResolver.resolve(
            ResolverInputs(crsUrlFlag = "https://x", tokenFlag = "flagtok", tokenEnv = "envtok"),
            config,
        )
        assertEquals("flagtok", target.token)
    }

    @Test
    fun `unresolved target throws`() {
        assertFailsWith<ConfigResolutionException> {
            TargetResolver.resolve(ResolverInputs(), CrsctlConfig.EMPTY)
        }
    }

    @Test
    fun `unknown named profile throws`() {
        assertFailsWith<ConfigResolutionException> {
            TargetResolver.resolve(ResolverInputs(envFlag = "nope"), config)
        }
    }

    @Test
    fun `malformed url with a space throws`() {
        assertFailsWith<ConfigResolutionException> {
            TargetResolver.resolve(ResolverInputs(crsUrlFlag = "not a url"), config)
        }
    }

    @Test
    fun `non-http scheme throws`() {
        assertFailsWith<ConfigResolutionException> {
            TargetResolver.resolve(ResolverInputs(crsUrlFlag = "ftp://x"), config)
        }
    }

    @Test
    fun `url with empty host throws`() {
        assertFailsWith<ConfigResolutionException> {
            TargetResolver.resolve(ResolverInputs(crsUrlFlag = "http://"), config)
        }
    }

    @Test
    fun `valid https url passes`() {
        val target = TargetResolver.resolve(ResolverInputs(crsUrlFlag = "https://crs.example"), config)
        assertEquals("https://crs.example", target.crsUrl)
    }

    @Test
    fun `trailing slash is normalized off the url`() {
        val target = TargetResolver.resolve(ResolverInputs(crsUrlFlag = "https://crs.example/"), config)
        assertEquals("https://crs.example", target.crsUrl)
    }
}
