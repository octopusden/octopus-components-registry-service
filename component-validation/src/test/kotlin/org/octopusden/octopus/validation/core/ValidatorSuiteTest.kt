package org.octopusden.octopus.validation.core

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

private enum class FakeType : ValidationType {
    A,
    B,
    ;

    override val id get() = name
}

private class OkValidator(
    override val type: ValidationType,
) : Validator<String> {
    override fun validate(input: String) = ValidationResult(type, Status.OK)
}

private class ThrowingValidator(
    override val type: ValidationType,
) : Validator<String> {
    override fun validate(input: String): ValidationResult = throw IllegalStateException("boom")
}

private class FakeSuite(
    override val validators: List<Validator<String>>,
) : ValidatorSuite<String>()

class ValidatorSuiteTest {
    @Test
    @DisplayName("SYS-068: a throwing validator does not sink the other validators' results (D6)")
    fun `SYS-068 throwing validator is isolated as an ERROR result`() {
        val suite = FakeSuite(listOf(OkValidator(FakeType.A), ThrowingValidator(FakeType.B)))

        val results = suite.validate("input")

        assertEquals(2, results.size)
        assertEquals(Status.OK, results[0].status)
        assertEquals(Status.ERROR, results[1].status)
        assertEquals(FakeType.B, results[1].type)
        requireNotNull(results[1].message) { "ERROR result should carry a message" }
    }

    @Test
    @DisplayName("SYS-068: a suite with no throwing validators returns all results unchanged")
    fun `SYS-068 all-ok suite returns every result as-is`() {
        val suite = FakeSuite(listOf(OkValidator(FakeType.A), OkValidator(FakeType.B)))

        val results = suite.validate("input")

        assertEquals(listOf(Status.OK, Status.OK), results.map { it.status })
    }
}
