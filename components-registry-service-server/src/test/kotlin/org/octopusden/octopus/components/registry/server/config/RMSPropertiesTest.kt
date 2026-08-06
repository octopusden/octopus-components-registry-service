package org.octopusden.octopus.components.registry.server.config

import jakarta.validation.Validation
import jakarta.validation.Validator
import jakarta.validation.ValidatorFactory
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration

class RMSPropertiesTest {
    private fun props(
        enabled: Boolean,
        url: String,
    ) = RMSProperties(enabled = enabled, url = url)

    @Test
    @DisplayName("disabled with a blank url is valid")
    fun `disabled with blank url passes`() {
        assertEquals(0, validator.validate(props(enabled = false, url = "")).size)
    }

    @Test
    @DisplayName("enabled with a non-blank url is valid")
    fun `enabled with configured url passes`() {
        assertEquals(0, validator.validate(props(enabled = true, url = "https://rms.example.com")).size)
    }

    @Test
    @DisplayName("enabled with a blank url is rejected")
    fun `enabled with blank url fails`() {
        val violations = validator.validate(props(enabled = true, url = ""))
        assertEquals(1, violations.size)
        assertEquals("urlConfiguredWhenEnabled", violations.first().propertyPath.toString())
    }

    @Test
    @DisplayName("enabled with a whitespace-only url is rejected")
    fun `enabled with whitespace url fails`() {
        val violations = validator.validate(props(enabled = true, url = "   "))
        assertEquals(1, violations.size)
        assertEquals("urlConfiguredWhenEnabled", violations.first().propertyPath.toString())
    }

    @Test
    @DisplayName("a zero connectTimeout is rejected — Duration.ZERO means 'wait forever,' not 'fail fast'")
    fun `zero connectTimeout fails`() {
        val violations = validator.validate(RMSProperties(connectTimeout = Duration.ZERO))
        assertTrue(violations.any { it.propertyPath.toString() == "connectTimeoutPositive" })
    }

    @Test
    @DisplayName("a negative readTimeout is rejected")
    fun `negative readTimeout fails`() {
        val violations = validator.validate(RMSProperties(readTimeout = Duration.ofSeconds(-1)))
        assertTrue(violations.any { it.propertyPath.toString() == "readTimeoutPositive" })
    }

    @Test
    @DisplayName("default connect/read timeouts are valid")
    fun `default timeouts pass`() {
        assertEquals(0, validator.validate(RMSProperties()).size)
    }

    companion object {
        private lateinit var factory: ValidatorFactory
        private lateinit var validator: Validator

        @JvmStatic
        @BeforeAll
        fun setUp() {
            factory = Validation.buildDefaultValidatorFactory()
            validator = factory.validator
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            factory.close()
        }
    }
}
