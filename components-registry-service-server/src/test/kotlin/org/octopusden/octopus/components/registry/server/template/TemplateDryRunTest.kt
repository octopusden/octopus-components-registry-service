package org.octopusden.octopus.components.registry.server.template

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.service.impl.ActiveStatus
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus

/** Begins, commits and rolls back nothing: the dry run's transaction handling is not under test here. */
private class NoTransactions : AbstractPlatformTransactionManager() {
    override fun doGetTransaction(): Any = Any()

    override fun doBegin(
        transaction: Any,
        definition: TransactionDefinition,
    ) = Unit

    override fun doCommit(status: DefaultTransactionStatus) = Unit

    override fun doRollback(status: DefaultTransactionStatus) = Unit
}

class TemplateDryRunTest {
    @Test
    @DisplayName("Decision 8: a unique-key race in today's create is a problem on name, not an error response")
    fun uniqueKeyRaceIsAProblem() {
        val dryRun =
            TemplateDryRun(
                parameters = ParameterChecker({ list -> TemplateFields.staticValues(list) ?: setOf("plugin") }) { ActiveStatus.ACTIVE },
                lists = { list -> TemplateFields.staticValues(list) ?: setOf("plugin") },
                defaults = { EXAMPLE_DEFAULTS },
                create = { throw DataIntegrityViolationException("duplicate key value violates unique constraint") },
                transactionManager = NoTransactions(),
            )
        val input =
            TemplateInput(
                parameters =
                    mapOf(
                        "CLIENT_CODE" to listOf("ACME"),
                        "PLUGIN_CODE" to listOf("CORE"),
                        "PLUGIN_NAME" to listOf("Core API"),
                    ),
            )

        val run = dryRun.run(parsedTemplate(), input, caller = "jdoe", commit = true)

        assertFalse(run.valid)
        assertEquals(listOf("name"), run.problems.single().fields)
        assertEquals(setOf("CLIENT_CODE", "PLUGIN_CODE"), run.problems.single().parameters)
    }
}
