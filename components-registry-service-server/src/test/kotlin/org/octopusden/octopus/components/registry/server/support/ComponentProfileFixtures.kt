package org.octopusden.octopus.components.registry.server.support

private const val REGULAR_NAME_PATTERN = "^(?!.*(solution|dmp-bundle)).*$"
private const val REGULAR_NAME_MESSAGE =
    "A regular component's key cannot contain solution or dmp-bundle. Choose the Solution or DMP Bundle profile."
const val SOLUTION_NAME_PATTERN = "^[a-z][a-z0-9-]*-solution(-[a-z0-9-]+)?$"
const val SOLUTION_NAME_MESSAGE = "A solution key contains -solution, e.g. payments-solution."
private const val DMP_NAME_PATTERN = "^[a-z][a-z0-9_-]*dmp-bundle[a-z0-9-]*$"
private const val DMP_NAME_MESSAGE = "A DMP bundle key contains dmp-bundle, e.g. payments-dmp-bundle."

/** Flattened properties of one profile, as they arrive under `components-registry.component-profiles`. */
fun profileProperties(
    id: String,
    kind: String? = "regular",
    title: String? = "Title of $id",
    description: String? = "Description of $id",
    order: String? = "10",
    external: String? = "true",
    explicit: String? = "ask",
    solution: String? = null,
    rules: Map<String, Map<String, String>> = emptyMap(),
    extra: Map<String, String> = emptyMap(),
): Map<String, String> =
    buildMap {
        kind?.let { put("$id.kind", it) }
        title?.let { put("$id.title", it) }
        description?.let { put("$id.description", it) }
        order?.let { put("$id.order", it) }
        external?.let { put("$id.classification.external", it) }
        explicit?.let { put("$id.classification.explicit", it) }
        solution?.let { put("$id.classification.solution", it) }
        rules.forEach { (path, rule) -> rule.forEach { (key, value) -> put("$id.rules.$path.$key", value) } }
        extra.forEach { (key, value) -> put("$id.$key", value) }
    }

fun rule(
    pattern: String,
    message: String,
) = mapOf("pattern" to pattern, "message" to message)

/** The four profiles of the design example. */
fun designExampleProperties(): Map<String, String> =
    profileProperties(
        "regular-external",
        order = "10",
        external = "true",
        explicit = "ask",
        rules = mapOf("name" to rule(REGULAR_NAME_PATTERN, REGULAR_NAME_MESSAGE)),
    ) +
        profileProperties(
            "regular-internal",
            order = "20",
            external = "false",
            explicit = "ask",
            rules = mapOf("name" to rule(REGULAR_NAME_PATTERN, REGULAR_NAME_MESSAGE)),
        ) +
        profileProperties(
            "solution",
            order = "30",
            external = "true",
            explicit = "true",
            solution = "true",
            rules = mapOf("name" to rule(SOLUTION_NAME_PATTERN, SOLUTION_NAME_MESSAGE)),
        ) +
        profileProperties(
            "dmp-bundle",
            order = "40",
            external = "true",
            explicit = "true",
            solution = "true",
            rules = mapOf("name" to rule(DMP_NAME_PATTERN, DMP_NAME_MESSAGE)),
        )
