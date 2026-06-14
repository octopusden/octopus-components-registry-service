package org.octopusden.octopus.components.registry.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.octopusden.octopus.components.registry.cli.CliContext
import org.octopusden.octopus.components.registry.cli.client.CrsClient
import org.octopusden.octopus.components.registry.cli.client.QueryParams
import org.octopusden.octopus.components.registry.cli.model.EmployeeMatchResponse
import org.octopusden.octopus.components.registry.cli.output.Renderer

/** `crsctl meta` — parent for the registry metadata / dictionary lookups. */
class MetaCommand : CliktCommand(
    name = "meta",
    help = "Registry metadata lookups (owners, systems, labels, build-systems, ...).",
    invokeWithoutSubcommand = false,
) {
    override fun run() = Unit
}

/**
 * A meta endpoint that returns a bare JSON array of strings. Each instance maps a CLI subcommand
 * name to its `/rest/api/4/components/meta/<name>` path.
 */
class MetaListCommand(
    name: String,
    private val path: String,
    help: String,
) : CliktCommand(name = name, help = help) {
    private val ctx by requireObject<CliContext>()

    override fun run() = runCommand {
        val client = ctx.client()
        val values = client.getJson(path, ListSerializer(String.serializer()))
        render(
            ctx,
            json = { Renderer.renderJson(values, ListSerializer(String.serializer())) },
            table = { Renderer.renderTable(listOf("VALUE"), values.map { listOf(it) }) },
        )
    }
}

/**
 * `crsctl meta employees --search <q>` — the authenticated employee lookup. Requires a token; a
 * 401/403 from the server surfaces (via the shared error handler) as exit code AUTH_REQUIRED.
 */
class MetaEmployeesCommand : CliktCommand(
    name = "employees",
    help = "Search employees (requires login). On 401/403 exits with AUTH_REQUIRED.",
) {
    private val ctx by requireObject<CliContext>()
    private val search by option("--search", help = "Required search term (username / name fragment).").required()

    override fun run() = runCommand {
        val client: CrsClient = ctx.authedClient()
        val query = QueryParams.builder().add("search", search).build()
        val matches = client.getJson(
            "/rest/api/4/components/meta/employees",
            ListSerializer(EmployeeMatchResponse.serializer()),
            query,
        )
        render(
            ctx,
            json = { Renderer.renderJson(matches, ListSerializer(EmployeeMatchResponse.serializer())) },
            table = {
                Renderer.renderTable(
                    headers = listOf("USERNAME", "ACTIVE"),
                    rows = matches.map { listOf(it.username, it.active.toString()) },
                )
            },
        )
    }
}

/**
 * Registers all meta subcommands on a fresh [MetaCommand]. Centralised so the CLI option -> spec path
 * mapping lives in exactly one place.
 */
fun metaCommand(): MetaCommand = MetaCommand().subcommands(
    MetaListCommand("build-systems", "/rest/api/4/components/meta/build-systems", "List build systems in use."),
    MetaListCommand("client-codes", "/rest/api/4/components/meta/client-codes", "List client codes in use."),
    MetaListCommand("escrow-generations", "/rest/api/4/components/meta/escrow-generations", "List escrow generations."),
    MetaListCommand("group-keys", "/rest/api/4/components/meta/group-keys", "List group keys in use."),
    MetaListCommand("java-versions", "/rest/api/4/components/meta/java-versions", "List Java versions in use."),
    MetaListCommand("jira-project-keys", "/rest/api/4/components/meta/jira-project-keys", "List Jira project keys."),
    MetaListCommand("labels", "/rest/api/4/components/meta/labels", "List labels currently in use."),
    MetaListCommand("labels-dictionary", "/rest/api/4/components/meta/labels/dictionary", "List all known labels (dictionary)."),
    MetaListCommand("maven-versions", "/rest/api/4/components/meta/maven-versions", "List Maven versions in use."),
    MetaListCommand("owners", "/rest/api/4/components/meta/owners", "List component owners in use."),
    MetaListCommand(
        "parent-component-names",
        "/rest/api/4/components/meta/parent-component-names",
        "List parent component names.",
    ),
    MetaListCommand("repository-types", "/rest/api/4/components/meta/repository-types", "List repository types."),
    MetaListCommand("systems", "/rest/api/4/components/meta/systems", "List systems currently in use."),
    MetaListCommand("systems-dictionary", "/rest/api/4/components/meta/systems/dictionary", "List all known systems (dictionary)."),
    MetaEmployeesCommand(),
)
