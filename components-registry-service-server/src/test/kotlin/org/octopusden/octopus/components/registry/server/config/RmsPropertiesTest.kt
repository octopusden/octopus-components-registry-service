package org.octopusden.octopus.components.registry.server.config

import jakarta.validation.Validation
import jakarta.validation.Validator
import jakarta.validation.ValidatorFactory
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class RmsPropertiesTest {
    private fun props(
        enabled: Boolean,
        url: String,
    ) = RmsProperties(enabled = enabled, url = url)

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
