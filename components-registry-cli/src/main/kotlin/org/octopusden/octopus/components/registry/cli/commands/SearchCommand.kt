package org.octopusden.octopus.components.registry.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.boolean
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.builtins.ListSerializer
import org.octopusden.octopus.components.registry.cli.CliContext
import org.octopusden.octopus.components.registry.cli.client.QueryParams
import org.octopusden.octopus.components.registry.cli.model.AsCodeSearchHit
import org.octopusden.octopus.components.registry.cli.model.AsCodeSearchResponse
import org.octopusden.octopus.components.registry.cli.output.Renderer

/**
 * `crsctl search <query>` — grep every component's as-code view (SYS-100), the replacement for
 * grepping the old Groovy DSL files.
 *
 * Table output is grep-shaped, one line per match: `<component>:<line>: <text>`, with the enclosing
 * block path appended when the match sits inside a nested block (e.g. a version range). JSON output
 * is the top-level array of matching components (the list-shaped-command convention); when the
 * server cut the result at `--limit`, a warning goes to STDERR in both modes.
 */
class SearchCommand :
    CliktCommand(
        name = "search",
        help =
            "Search the as-code text of every component (artifacts, versions, VCS URLs, people, ...). " +
                "Case-insensitive substring; --regex for a regular expression.",
    ) {
    private val ctx by requireObject<CliContext>()

    private val query by argument("query", help = "Text to find (at least 2 characters).")
    private val regex by option("--regex", help = "Treat the query as a case-insensitive regular expression.").flag()
    private val archived by option("--archived", help = "Only archived (true) or only active (false) components.").boolean()
    private val limit by option("--limit", help = "Maximum number of components to return (server default 100).").int()
    private val maxMatches by option(
        "--max-matches",
        help = "Maximum matching lines shown per component (server default 20).",
    ).int()

    override fun run() =
        runCommand {
            val params =
                QueryParams
                    .builder()
                    .add("q", query)
                    .add("regex", if (regex) true else null)
                    .add("archived", archived)
                    .add("limit", limit)
                    .add("maxMatchesPerComponent", maxMatches)
                    .build()
            val response = ctx.client().getJson(SEARCH_PATH, AsCodeSearchResponse.serializer(), params)
            render(
                ctx,
                json = { Renderer.renderJson(response.results, ListSerializer(AsCodeSearchHit.serializer())) },
                table = { renderGrep(response) },
            )
            if (response.truncated) {
                echo(
                    "warning: showing ${response.results.size} of ${response.totalComponents} matching components; " +
                        "raise --limit or narrow the query",
                    err = true,
                )
            }
        }

    companion object {
        const val SEARCH_PATH = "/rest/api/4/components/as-code/search"

        internal fun renderGrep(response: AsCodeSearchResponse): String =
            response.results.joinToString("\n") { hit ->
                val lines =
                    hit.matches.map { m ->
                        // path[0] is the component block itself — only deeper nesting is worth showing.
                        val nested = m.path.drop(1)
                        val where = if (nested.isEmpty()) "" else nested.joinToString(" > ", prefix = "  [", postfix = "]")
                        "${hit.componentKey}:${m.line}: ${m.text}$where"
                    }
                val more = hit.matchCount - hit.matches.size
                (if (more > 0) lines + "${hit.componentKey}: ... $more more" else lines).joinToString("\n")
            }
    }
}
