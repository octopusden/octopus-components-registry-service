package org.octopusden.octopus.components.registry.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import kotlinx.serialization.builtins.ListSerializer
import org.octopusden.octopus.components.registry.cli.CliContext
import org.octopusden.octopus.components.registry.cli.client.CrsClient
import org.octopusden.octopus.components.registry.cli.model.ComponentDetailResponse
import org.octopusden.octopus.components.registry.cli.model.FieldOverrideResponse
import org.octopusden.octopus.components.registry.cli.output.Renderer
import java.util.UUID

/** `crsctl component` — parent for single-component operations (get / as-code / overrides). */
class ComponentCommand :
    CliktCommand(
        name = "component",
        help = "Inspect a single component (get / as-code / overrides).",
        invokeWithoutSubcommand = false,
    ) {
    override fun run() = Unit
}

/** `crsctl component get <idOrName>` — fetch the full component detail. */
class ComponentGetCommand :
    CliktCommand(
        name = "get",
        help = "Get a component's full detail by id (UUID) or name. Read is anonymous.",
    ) {
    private val ctx by requireObject<CliContext>()
    private val idOrName by argument(name = "ID_OR_NAME", help = "Component id (UUID) or name.")

    override fun run() =
        runCommand {
            val client = ctx.client()
            val detail = fetchDetail(client, idOrName)
            render(
                ctx,
                json = { Renderer.renderJson(detail, ComponentDetailResponse.serializer()) },
                table = { detailTable(detail) },
            )
        }
}

/** `crsctl component as-code <idOrName>` — print the Groovy-style component source verbatim. */
class ComponentAsCodeCommand :
    CliktCommand(
        name = "as-code",
        help = "Print a component's as-code (Groovy-style) source by id or name. Output is raw text/plain.",
    ) {
    private val ctx by requireObject<CliContext>()
    private val idOrName by argument(name = "ID_OR_NAME", help = "Component id (UUID) or name.")

    override fun run() =
        runCommand {
            val client = ctx.client()
            // text/plain endpoint: print exactly what the server returns, no JSON/table shaping.
            emit(client.getText("/rest/api/4/components/${encodePathSegment(idOrName)}/as-code"))
        }
}

/**
 * `crsctl component overrides <idOrUuidOrName>` — list a component's field-overrides.
 *
 * The field-overrides endpoint requires the component's UUID in the path. If the argument already is
 * a valid UUID it is used directly; otherwise it is treated as a name and resolved to a UUID via a
 * `component get` lookup (reading the detail's `id`) before the field-overrides call.
 */
class ComponentOverridesCommand :
    CliktCommand(
        name = "overrides",
        help = "List a component's field-overrides. A UUID is used directly; a name is first resolved " +
            "to its UUID via a component lookup.",
    ) {
    private val ctx by requireObject<CliContext>()
    private val idOrUuidOrName by argument(name = "ID_OR_NAME", help = "Component UUID or name.")

    override fun run() =
        runCommand {
            val client = ctx.client()
            val uuid = if (isUuid(idOrUuidOrName)) {
                idOrUuidOrName
            } else {
                fetchDetail(client, idOrUuidOrName).id
            }
            val overrides = client.getJson(
                "/rest/api/4/components/${encodePathSegment(uuid)}/field-overrides",
                ListSerializer(FieldOverrideResponse.serializer()),
            )
            render(
                ctx,
                json = { Renderer.renderJson(overrides, ListSerializer(FieldOverrideResponse.serializer())) },
                table = { overridesTable(overrides) },
            )
        }
}

/** Shared: GET /rest/api/4/components/{idOrName} (no resolve — server accepts id or name). */
private fun fetchDetail(
    client: CrsClient,
    idOrName: String,
): ComponentDetailResponse = client.getJson("/rest/api/4/components/${encodePathSegment(idOrName)}", ComponentDetailResponse.serializer())

/** True when [value] parses as a canonical UUID. */
private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess

private fun detailTable(detail: ComponentDetailResponse): String =
    Renderer.renderTable(
        headers = listOf("FIELD", "VALUE"),
        rows = listOf(
            listOf("id", detail.id),
            listOf("name", detail.name),
            listOf("displayName", detail.displayName),
            listOf("owner", detail.componentOwner),
            listOf("system", detail.system),
            listOf("productType", detail.productType),
            listOf("archived", detail.archived.toString()),
            listOf("canBeParent", detail.canBeParent.toString()),
            listOf("labels", detail.labels.joinToString(", ")),
        ),
    )

private fun overridesTable(overrides: List<FieldOverrideResponse>): String =
    Renderer.renderTable(
        headers = listOf("ID", "ATTRIBUTE", "ROW_TYPE", "VERSION_RANGE"),
        rows = overrides.map { listOf(it.id, it.overriddenAttribute, it.rowType, it.versionRange) },
    )
