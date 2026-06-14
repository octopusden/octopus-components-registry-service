package org.octopusden.octopus.components.registry.cli.commands

import org.octopusden.octopus.components.registry.cli.CrsClientFactory
import org.octopusden.octopus.components.registry.cli.client.CrsClient
import org.octopusden.octopus.components.registry.cli.client.HttpExchange
import org.octopusden.octopus.components.registry.cli.config.CrsctlConfig
import org.octopusden.octopus.components.registry.cli.crsctl
import org.octopusden.octopus.components.registry.cli.rewriteHelpArgs
import org.octopusden.octopus.components.registry.cli.runCli
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers FIX 1: parse-time Clikt outcomes (--help / --version / usage errors) are mapped onto the
 * crsctl exit-code contract instead of Clikt's own status codes / human-text behaviour.
 */
class CliMainTest {

    private val neverExchange = HttpExchange { error("no HTTP call expected during parse-time tests") }

    private fun cli() = crsctl(
        configLoader = { CrsctlConfig.EMPTY },
        clientFactory = CrsClientFactory { target -> CrsClient(target.crsUrl, target.token, neverExchange) },
    )

    /** Runs [block] with STDOUT captured; restores the original stream in a finally. */
    private fun captureStdout(block: () -> Int): Pair<Int, String> {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true, "UTF-8"))
        return try {
            val code = block()
            code to buffer.toString("UTF-8")
        } finally {
            System.setOut(original)
        }
    }

    /** Runs [block] with STDERR captured; restores the original stream in a finally. */
    private fun captureStderr(block: () -> Int): Pair<Int, String> {
        val original = System.err
        val buffer = ByteArrayOutputStream()
        System.setErr(PrintStream(buffer, true, "UTF-8"))
        return try {
            val code = block()
            code to buffer.toString("UTF-8")
        } finally {
            System.setErr(original)
        }
    }

    @Test
    fun `--help exits 0 and prints usage to stdout`() {
        val (code, out) = captureStdout { runCli(arrayOf("--help"), cli()) }
        assertEquals(0, code)
        assertTrue(out.contains("crsctl"), "help should name the program: $out")
    }

    @Test
    fun `no subcommand exits 0`() {
        // The root command renders help via its own echo and returns normally (no exception).
        val (code, _) = captureStdout { runCli(arrayOf(), cli()) }
        assertEquals(0, code)
    }

    @Test
    fun `--version exits 0`() {
        val (code, _) = captureStdout { runCli(arrayOf("--version"), cli()) }
        assertEquals(0, code)
    }

    @Test
    fun `missing required subcommand argument exits USAGE`() {
        val (code, err) = captureStderr { runCli(arrayOf("component"), cli()) }
        assertEquals(2, code)
        assertTrue(err.contains("\"message\""), "structured error JSON expected on stderr: $err")
    }

    @Test
    fun `invalid output choice exits USAGE`() {
        val (code, _) = captureStderr {
            runCli(arrayOf("components", "list", "-o", "bogus", "--crs-url=https://crs.example"), cli())
        }
        assertEquals(2, code)
    }

    @Test
    fun `unknown command exits USAGE`() {
        val (code, _) = captureStderr { runCli(arrayOf("totallyunknowncommand"), cli()) }
        assertEquals(2, code)
    }

    @Test
    fun `bare help prints root help to stdout exit 0`() {
        val (code, out) = captureStdout { runCli(arrayOf("help"), cli()) }
        assertEquals(0, code)
        assertTrue(out.contains("Commands:") && out.contains("components"), "root help expected: $out")
    }

    @Test
    fun `help with a nested command prints that command's help exit 0`() {
        val (code, out) = captureStdout { runCli(arrayOf("help", "components", "list"), cli()) }
        assertEquals(0, code)
        assertTrue(out.contains("components list") && out.contains("--archived"), "list help expected: $out")
    }

    @Test
    fun `help preserves preceding global options`() {
        val (code, out) = captureStdout { runCli(arrayOf("--env", "dev", "help", "meta"), cli()) }
        assertEquals(0, code)
        assertTrue(out.contains("employees"), "meta help expected: $out")
    }

    @Test
    fun `help for an unknown command exits USAGE`() {
        val (code, _) = captureStderr { runCli(arrayOf("help", "bogus"), cli()) }
        assertEquals(2, code)
    }

    @Test
    fun `help is listed in root help`() {
        val (_, out) = captureStdout { runCli(arrayOf("--help"), cli()) }
        assertTrue(Regex("(?m)^\\s*help\\b").containsMatchIn(out), "help command should be listed: $out")
    }

    @Test
    fun `rewriteHelpArgs maps leading help onto --help at each depth`() {
        assertEquals(listOf("--help"), rewriteHelpArgs(arrayOf("help")).toList())
        assertEquals(listOf("components", "list", "--help"), rewriteHelpArgs(arrayOf("help", "components", "list")).toList())
        // global options before the subcommand are preserved (both --opt value and --opt=value forms)
        assertEquals(listOf("--env", "dev", "meta", "--help"), rewriteHelpArgs(arrayOf("--env", "dev", "help", "meta")).toList())
        assertEquals(listOf("-o", "json", "--help"), rewriteHelpArgs(arrayOf("-o", "json", "help")).toList())
        assertEquals(listOf("--crs-url=https://x", "--help"), rewriteHelpArgs(arrayOf("--crs-url=https://x", "help")).toList())
    }

    @Test
    fun `rewriteHelpArgs leaves non-leading help untouched`() {
        // `help` as an argument to a subcommand (not the subcommand slot) must NOT become help mode.
        val args = arrayOf("component", "get", "help")
        assertEquals(args.toList(), rewriteHelpArgs(args).toList())
    }
}
