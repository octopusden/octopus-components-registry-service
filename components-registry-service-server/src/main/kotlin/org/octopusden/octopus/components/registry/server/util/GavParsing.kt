package org.octopusden.octopus.components.registry.server.util

/**
 * Parsed Maven GAV coordinates extracted from a single CSV entry.
 *
 * @param groupId    Maven groupId (first `:` segment)
 * @param artifactId Maven artifactId (second `:` segment)
 * @param extension  Optional type/extension (third `:` segment), null when absent
 * @param classifier Optional classifier (fourth `:` segment), null when absent
 */
internal data class MavenCoords(
    val groupId: String,
    val artifactId: String,
    val extension: String?,
    val classifier: String?,
)

/**
 * Splits a comma-separated value string into trimmed, non-empty tokens.
 *
 * Shared between [ImportServiceImpl] (write path) and
 * [DatabaseComponentRegistryResolver] (read path) to ensure consistent tokenisation.
 */
internal fun splitCsv(csv: String): List<String> =
    csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }

/**
 * Parses a single Maven GAV entry of the form `groupId:artifactId[:extension[:classifier]]`.
 *
 * Returns null when the entry has fewer than two `:` segments or when either the
 * groupId or artifactId segment is blank.  URL-format entries (`file://`, `http://`,
 * `https://`) are NOT filtered here — callers are responsible for skipping them
 * before invoking this function so that the parser remains pure.
 */
internal fun parseMavenGavEntry(entry: String): MavenCoords? {
    val parts = entry.split(":").map { it.trim() }
    if (parts.size < 2) return null
    val group = parts[0]
    val artifact = parts[1]
    if (group.isEmpty() || artifact.isEmpty()) return null
    val extension = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }
    val classifier = parts.getOrNull(3)?.takeIf { it.isNotEmpty() }
    return MavenCoords(group, artifact, extension, classifier)
}
