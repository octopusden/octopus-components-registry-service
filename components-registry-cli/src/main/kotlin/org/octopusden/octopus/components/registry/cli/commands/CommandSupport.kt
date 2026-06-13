package org.octopusden.octopus.components.registry.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import org.octopusden.octopus.components.registry.cli.CliContext
import org.octopusden.octopus.components.registry.cli.client.ExitCodes
import org.octopusden.octopus.components.registry.cli.output.OutputFormat
import org.octopusden.octopus.components.registry.cli.output.Renderer

/**
 * Runs [block], catching any thrown error, writing a structured error to STDERR via
 * [Renderer.renderError], and aborting with the mapped process exit code
 * ([ExitCodes.fromThrowable]).
 *
 * On success the command simply returns; a non-zero exit is signalled by throwing Clikt's
 * [ProgramResult], which Clikt translates into `System.exit` (and which the testing harness reports
 * as `statusCode`). [ProgramResult] itself is re-thrown untouched so an inner helper can short-circuit
 * with an explicit code.
 */
internal fun CliktCommand.runCommand(block: () -> Unit) {
    try {
        block()
    } catch (pr: ProgramResult) {
        throw pr
    } catch (t: Throwable) {
        echo(Renderer.renderError(t), err = true)
        throw ProgramResult(ExitCodes.fromThrowable(t).code)
    }
}

/** Writes [text] to STDOUT (no trailing extra newline beyond [echo]'s own). */
internal fun CliktCommand.emit(text: String) = echo(text)

/**
 * Emits either [json] or [table], honouring the resolved [OutputFormat] from [ctx]. Both are passed
 * as thunks so only the selected renderer runs.
 */
internal fun CliktCommand.render(
    ctx: CliContext,
    json: () -> String,
    table: () -> String,
) {
    val rendered = when (ctx.output) {
        OutputFormat.JSON -> json()
        OutputFormat.TABLE -> table()
    }
    emit(rendered)
}
