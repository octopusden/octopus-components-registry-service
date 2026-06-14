package org.octopusden.octopus.components.registry.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.boolean
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.builtins.ListSerializer
import org.octopusden.octopus.components.registry.cli.CliContext
import org.octopusden.octopus.components.registry.cli.client.CrsClient
import org.octopusden.octopus.components.registry.cli.client.QueryParams
import org.octopusden.octopus.components.registry.cli.model.ComponentSummaryResponse
import org.octopusden.octopus.components.registry.cli.model.PageComponentSummaryResponse
import org.octopusden.octopus.components.registry.cli.output.Renderer

/** `crsctl components` — parent for component-list operations. */
class ComponentsCommand : CliktCommand(
    name = "components",
    help = "List components in the registry.",
    invokeWithoutSubcommand = false,
) {
    override fun run() = Unit
}

/**
 * `crsctl components list` — query the components list with the full filter set, paging, and an
 * optional `--all` auto-paginate that walks every page until the server reports `last == true`.
 */
class ComponentsListCommand : CliktCommand(
    name = "list",
    help = "List components, optionally filtered. Reads are anonymous; no token is required.",
) {
    private val ctx by requireObject<CliContext>()

    // --- filters (CLI kebab-case -> v4.json listComponents query param) ---
    private val search by option("--search", help = "Free-text search across name/displayName.")
    private val owner by option("--owner", help = "Filter by component owner (repeatable).").multiple()
    private val system by option("--system", help = "Filter by system code (repeatable).").multiple()
    private val productType by option("--product-type", help = "Filter by product type.")
    private val buildSystem by option("--build-system", help = "Filter by build system (repeatable).").multiple()
    private val label by option("--label", help = "Filter by label (repeatable).").multiple()
    private val clientCode by option("--client-code", help = "Filter by client code (repeatable).").multiple()
    private val solution by option("--solution", help = "Filter solutions (true|false).").boolean()
    private val jiraProjectKey by option(
        "--jira-project-key",
        help = "Filter by Jira project key (repeatable).",
    ).multiple()
    private val jiraTechnical by option("--jira-technical", help = "Filter technical Jira projects (true|false).").boolean()
    private val vcsPath by option("--vcs-path", help = "Filter by VCS path.")
    private val productionBranch by option("--production-branch", help = "Filter by production branch.")
    private val parent by option(
        "--parent",
        help = "Filter by parent component name (repeatable).",
    ).multiple()
    private val groupKey by option("--group-key", help = "Filter by group key (repeatable).").multiple()
    private val archived by option("--archived", help = "Filter archived components (true|false).").boolean()
    private val canBeParent by option("--can-be-parent", help = "Filter components that can be parents (true|false).").boolean()
    private val distributionExplicit by option(
        "--distribution-explicit",
        help = "Filter by explicit distribution (true|false).",
    ).boolean()
    private val distributionExternal by option(
        "--distribution-external",
        help = "Filter by external distribution (true|false).",
    ).boolean()

    // --- paging ---
    private val page by option("--page", help = "Zero-based page number.").int()
    private val size by option("--size", help = "Page size.").int()
    private val sort by option("--sort", help = "Sort spec, e.g. name,asc (repeatable).").multiple()
    private val all by option(
        "--all",
        help = "Fetch every page (ignores --page; loops until the last page).",
    ).flag()

    override fun run() = runCommand {
        val client = ctx.client()
        val rows = if (all) fetchAll(client) else fetchOnePage(client)
        render(
            ctx,
            json = { Renderer.renderJson(rows, ListSerializer(ComponentSummaryResponse.serializer())) },
            table = { renderTable(rows) },
        )
    }

    private fun baseFilters(): QueryParams.Builder =
        QueryParams.builder()
            .add("search", search)
            .addAll("owner", owner.ifEmpty { null })
            .addAll("system", system.ifEmpty { null })
            .add("productType", productType)
            .addAll("buildSystem", buildSystem.ifEmpty { null })
            .addAll("labels", label.ifEmpty { null })
            .addAll("clientCode", clientCode.ifEmpty { null })
            .add("solution", solution)
            .addAll("jiraProjectKey", jiraProjectKey.ifEmpty { null })
            .add("jiraTechnical", jiraTechnical)
            .add("vcsPath", vcsPath)
            .add("productionBranch", productionBranch)
            .addAll("parentComponentName", parent.ifEmpty { null })
            .addAll("groupKey", groupKey.ifEmpty { null })
            .add("archived", archived)
            .add("canBeParent", canBeParent)
            .add("distributionExplicit", distributionExplicit)
            .add("distributionExternal", distributionExternal)

    private fun fetchOnePage(client: CrsClient): List<ComponentSummaryResponse> {
        val query = baseFilters().pageable(page = page, size = size, sort = sort.ifEmpty { null }).build()
        val response = client.getJson(COMPONENTS_PATH, PageComponentSummaryResponse.serializer(), query)
        return response.content.orEmpty()
    }

    /**
     * Walks pages starting at 0 (or the configured size), accumulating content until `last == true`.
     *
     * A misbehaving server that never sets `last` must not loop forever, so two extra guards apply:
     *  - if the response carries a non-null `totalPages`, stop once the next index would reach it;
     *  - an absolute [MAX_PAGES] safety cap, which (if hit) warns on STDERR and breaks.
     */
    private fun fetchAll(client: CrsClient): List<ComponentSummaryResponse> {
        val accumulated = mutableListOf<ComponentSummaryResponse>()
        var current = 0
        while (true) {
            val query = baseFilters().pageable(page = current, size = size, sort = sort.ifEmpty { null }).build()
            val response = client.getJson(COMPONENTS_PATH, PageComponentSummaryResponse.serializer(), query)
            accumulated += response.content.orEmpty()
            if (response.last == true) {
                break
            }
            // Defensive stop: a server that never reports `last` (or returns an empty page) must not loop forever.
            if (response.content.isNullOrEmpty()) {
                break
            }
            // Page-count bound: stop when the next page index would reach/exceed the reported total.
            val totalPages = response.totalPages
            if (totalPages != null && current + 1 >= totalPages) {
                break
            }
            // Absolute safety cap: never loop past MAX_PAGES even if the server keeps lying.
            if (current + 1 >= MAX_PAGES) {
                System.err.println("warning: --all stopped at the $MAX_PAGES-page safety cap")
                break
            }
            current++
        }
        return accumulated
    }

    private fun renderTable(rows: List<ComponentSummaryResponse>): String =
        Renderer.renderTable(
            headers = listOf("ID", "NAME", "OWNER", "SYSTEM"),
            rows = rows.map { listOf(it.id, it.name, it.componentOwner, it.system) },
        )

    companion object {
        const val COMPONENTS_PATH = "/rest/api/4/components"

        /** Absolute upper bound on `--all` page fetches, guarding against a server that never sets `last`. */
        const val MAX_PAGES = 10_000
    }
}
