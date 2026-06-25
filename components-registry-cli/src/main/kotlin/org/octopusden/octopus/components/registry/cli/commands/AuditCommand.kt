package org.octopusden.octopus.components.registry.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.boolean
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.builtins.ListSerializer
import org.octopusden.octopus.components.registry.cli.CliContext
import org.octopusden.octopus.components.registry.cli.client.QueryParams
import org.octopusden.octopus.components.registry.cli.model.AuditLogResponse
import org.octopusden.octopus.components.registry.cli.model.PageAuditLogResponse
import org.octopusden.octopus.components.registry.cli.output.Renderer

/**
 * `crsctl audit` — parent for the audit-log queries.
 *
 * Every audit subcommand REQUIRES a bearer token (the server gates these behind ACCESS_AUDIT). The
 * token is resolved through [CliContext.authedClient], which mirrors `whoami` (explicit `--token` /
 * `CRS_TOKEN`, else a stored refresh token exchanged for an access token). When no credential can be
 * resolved it throws [org.octopusden.octopus.components.registry.cli.auth.AuthRequiredException]
 * BEFORE any HTTP call, so a missing login surfaces as a clear AUTH_REQUIRED (exit 4) rather than a
 * generic server 401.
 */
class AuditCommand : CliktCommand(
    name = "audit",
    help = "Query the audit log (requires login and the ACCESS_AUDIT permission).",
    invokeWithoutSubcommand = false,
) {
    override fun run() = Unit
}

/**
 * `crsctl audit recent` — GET /rest/api/4/audit/recent with the full filter set.
 *
 * CLI kebab option -> v4.json query param: --entity-type=entityType, --entity-id=entityId,
 * --changed-by=changedBy, --action=action, --source=source, --from=from, --to=to,
 * --include-migrated=includeMigrated, plus the standard pageable (--page/--size/--sort).
 *
 * `--include-migrated` is tri-state: omitted -> not sent (server default applies); `true`/`false`
 * -> sent verbatim.
 */
class AuditRecentCommand : CliktCommand(
    name = "recent",
    help = "List recent audit-log entries, optionally filtered. Requires login (ACCESS_AUDIT).",
) {
    private val ctx by requireObject<CliContext>()

    // --- filters (CLI kebab-case -> v4.json getRecentChanges query param) ---
    private val entityType by option("--entity-type", help = "Filter by entity type (spec: entityType).")
    private val entityId by option("--entity-id", help = "Filter by entity id (spec: entityId).")
    private val changedBy by option("--changed-by", help = "Filter by the user who made the change (spec: changedBy).")
    private val action by option("--action", help = "Filter by action (spec: action).")
    private val source by option("--source", help = "Filter by change source (spec: source).")
    private val from by option("--from", help = "Lower bound (inclusive), ISO-8601 date-time (spec: from).")
    private val to by option("--to", help = "Upper bound (exclusive), ISO-8601 date-time (spec: to).")
    private val includeMigrated by option(
        "--include-migrated",
        help = "Include migrated entries (true|false). Omitted means the server default.",
    ).boolean()

    // --- paging ---
    private val page by option("--page", help = "Zero-based page number.").int()
    private val size by option("--size", help = "Page size.").int()
    private val sort by option("--sort", help = "Sort spec, e.g. changedAt,desc (repeatable).").multiple()

    override fun run() = runCommand {
        val client = ctx.authedClient()
        val query = QueryParams.builder()
            .add("entityType", entityType)
            .add("entityId", entityId)
            .add("changedBy", changedBy)
            .add("action", action)
            .add("source", source)
            .add("from", from)
            .add("to", to)
            .add("includeMigrated", includeMigrated)
            .pageable(page = page, size = size, sort = sort.ifEmpty { null })
            .build()
        val response = client.getJson(AUDIT_RECENT_PATH, PageAuditLogResponse.serializer(), query)
        renderAuditPage(ctx, response)
    }

    companion object {
        const val AUDIT_RECENT_PATH = "/rest/api/4/audit/recent"
    }
}

/**
 * `crsctl audit <entityType> <entityId>` — GET /rest/api/4/audit/{entityType}/{entityId}.
 *
 * Both path segments are percent-encoded via [encodePathSegment]. Supports `--include-migrated` plus
 * the standard pageable (--page/--size/--sort). Requires login (ACCESS_AUDIT).
 */
class AuditHistoryCommand : CliktCommand(
    name = "history",
    help = "Show the audit history for a single entity. Requires login (ACCESS_AUDIT).",
) {
    private val ctx by requireObject<CliContext>()

    private val entityType by argument("entity-type", help = "Entity type (e.g. COMPONENT).")
    private val entityId by argument("entity-id", help = "Entity id.")

    private val includeMigrated by option(
        "--include-migrated",
        help = "Include migrated entries (true|false). Omitted means the server default.",
    ).boolean()

    private val page by option("--page", help = "Zero-based page number.").int()
    private val size by option("--size", help = "Page size.").int()
    private val sort by option("--sort", help = "Sort spec, e.g. changedAt,desc (repeatable).").multiple()

    override fun run() = runCommand {
        val client = ctx.authedClient()
        val path = "/rest/api/4/audit/${encodePathSegment(entityType)}/${encodePathSegment(entityId)}"
        val query = QueryParams.builder()
            .add("includeMigrated", includeMigrated)
            .pageable(page = page, size = size, sort = sort.ifEmpty { null })
            .build()
        val response = client.getJson(path, PageAuditLogResponse.serializer(), query)
        renderAuditPage(ctx, response)
    }
}

/** Shared rendering of a [PageAuditLogResponse] as either JSON (the rows) or a table. */
private fun CliktCommand.renderAuditPage(ctx: CliContext, response: PageAuditLogResponse) {
    val rows = response.content.orEmpty()
    render(
        ctx,
        json = { Renderer.renderJson(rows, ListSerializer(AuditLogResponse.serializer())) },
        table = {
            Renderer.renderTable(
                headers = listOf("CHANGED_AT", "ACTION", "ENTITY_TYPE", "COMPONENT_KEY", "ENTITY_ID", "CHANGED_BY"),
                rows = rows.map {
                    listOf(it.changedAt, it.action, it.entityType, it.componentKey, it.entityId, it.changedBy)
                },
            )
        },
    )
}

/**
 * Builds the `audit` command tree (parent + subcommands).
 *
 * `recent` and `history` are sibling subcommands because Clikt cannot mix free-standing positional
 * arguments on a parent with subcommand dispatch (a bare `audit COMPONENT 123` would be read as a
 * subcommand named "COMPONENT"). `audit history <entityType> <entityId>` is the per-entity form.
 */
fun auditCommand(): AuditCommand = AuditCommand().subcommands(
    AuditRecentCommand(),
    AuditHistoryCommand(),
)
