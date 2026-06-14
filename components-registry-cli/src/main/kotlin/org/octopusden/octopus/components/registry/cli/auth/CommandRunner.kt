package org.octopusden.octopus.components.registry.cli.auth

/**
 * The outcome of running an external process: its exit code plus captured STDOUT / STDERR.
 */
data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

/**
 * Functional seam over launching an external process. Production code uses [ProcessCommandRunner]
 * (a real [ProcessBuilder]); tests substitute an in-memory fake so the macOS Keychain is never
 * touched.
 *
 * [run] executes [args] (argv-style, no shell) optionally feeding [stdin] to the process's standard
 * input, and returns a [CommandResult].
 */
fun interface CommandRunner {
    fun run(args: List<String>, stdin: String?): CommandResult
}

/**
 * Default [CommandRunner] backed by [ProcessBuilder]. STDOUT and STDERR are captured separately so
 * the caller can distinguish a printed secret from a diagnostic message.
 */
class ProcessCommandRunner : CommandRunner {
    override fun run(args: List<String>, stdin: String?): CommandResult {
        val process = ProcessBuilder(args)
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        if (stdin != null) {
            process.outputStream.use { it.write(stdin.toByteArray(Charsets.UTF_8)) }
        } else {
            process.outputStream.close()
        }
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val err = process.errorStream.readBytes().toString(Charsets.UTF_8)
        val exit = process.waitFor()
        return CommandResult(exitCode = exit, stdout = out, stderr = err)
    }
}
