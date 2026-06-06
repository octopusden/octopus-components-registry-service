package org.octopusden.octopus.components.registry.server.mapper

import org.octopusden.releng.versions.IVersionInfo
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionRange
import org.octopusden.releng.versions.VersionRangeFactory

/**
 * Sample-points containment heuristic for [rangeApplies] (TD-010).
 *
 * Returns true when every sampled point of [childRange] is contained in
 * [parentRange]. Equality is handled by the caller.
 */
internal fun rangeAppliesByContainment(
    parentRange: String,
    childRange: String,
    versionRangeFactory: VersionRangeFactory,
    numericVersionFactory: NumericVersionFactory,
): Boolean {
    val parent =
        try {
            versionRangeFactory.create(parentRange)
        } catch (_: Exception) {
            return false
        }
    val child =
        try {
            versionRangeFactory.create(childRange)
        } catch (_: Exception) {
            return false
        }

    val samples = sampleChildRangePoints(childRange, child, numericVersionFactory)
    if (samples.isEmpty()) {
        return false
    }
    return samples.all { parent.containsVersion(it) }
}

private data class ParsedSegment(
    val includeLeft: Boolean,
    val left: String?,
    val includeRight: Boolean,
    val right: String?,
)

private fun parseSegments(range: String): List<ParsedSegment> =
    range.split("(?<=[])])\\s*,\\s*(?=[\\[(])".toRegex())
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { parseSegment(it) }

private fun parseSegment(segment: String): ParsedSegment {
    if (segment.startsWith("[") && segment.endsWith("]") && !segment.contains(",")) {
        val version = segment.substring(1, segment.length - 1)
        return ParsedSegment(includeLeft = true, left = version, includeRight = true, right = version)
    }

    val commaIdx = segment.indexOf(',')
    require(commaIdx > 0) { "Bad segment: $segment" }

    val open = segment[0]
    val close = segment[segment.length - 1]
    val leftPart = segment.substring(1, commaIdx).trim()
    val rightPart = segment.substring(commaIdx + 1, segment.length - 1).trim()

    return ParsedSegment(
        includeLeft = open == '[',
        left = leftPart.ifEmpty { null },
        includeRight = close == ']',
        right = rightPart.ifEmpty { null },
    )
}

private fun sampleChildRangePoints(
    childRangeLiteral: String,
    childRange: VersionRange,
    numericVersionFactory: NumericVersionFactory,
): List<IVersionInfo> {
    val samples = linkedSetOf<IVersionInfo>()
    for (segment in parseSegments(childRangeLiteral)) {
        sampleSegment(segment, childRange, numericVersionFactory, samples)
    }
    return samples.toList()
}

private fun sampleSegment(
    segment: ParsedSegment,
    childRange: VersionRange,
    numericVersionFactory: NumericVersionFactory,
    samples: MutableSet<IVersionInfo>,
) {
    if (segment.left != null && segment.right != null && segment.left == segment.right) {
        addIfContained(segment.left, childRange, numericVersionFactory, samples)
        return
    }

    segment.left?.let { left ->
        if (segment.includeLeft) {
            addIfContained(left, childRange, numericVersionFactory, samples)
        } else {
            epsilonAbove(left).forEach { addIfContained(it, childRange, numericVersionFactory, samples) }
        }
    } ?: run {
        listOf("0", "1.0").forEach { addIfContained(it, childRange, numericVersionFactory, samples) }
    }

    segment.right?.let { right ->
        if (segment.includeRight) {
            addIfContained(right, childRange, numericVersionFactory, samples)
        } else {
            epsilonBelow(right).forEach { addIfContained(it, childRange, numericVersionFactory, samples) }
        }
    } ?: segment.left?.let { left ->
        epsilonAbove(left).forEach { addIfContained(it, childRange, numericVersionFactory, samples) }
        listOf("99.0", "100.0").forEach { addIfContained(it, childRange, numericVersionFactory, samples) }
    }

    if (segment.left != null && segment.right != null) {
        interiorProbes(segment.left, segment.right).forEach {
            addIfContained(it, childRange, numericVersionFactory, samples)
        }
    }
}

private fun addIfContained(
    rawVersion: String,
    childRange: VersionRange,
    numericVersionFactory: NumericVersionFactory,
    samples: MutableSet<IVersionInfo>,
) {
    try {
        val version = numericVersionFactory.create(rawVersion.trim())
        if (childRange.containsVersion(version)) {
            samples.add(version)
        }
    } catch (_: Exception) {
        // ignore unparsable probe strings
    }
}

private fun epsilonAbove(bound: String): List<String> =
    listOf("$bound.1", bumpLastNumericComponent(bound)).distinct()

private fun epsilonBelow(bound: String): List<String> {
    val parts = bound.split(".")
    if (parts.size >= 2) {
        val last = parts.last().toIntOrNull()
        if (last != null && last > 0) {
            return listOf(parts.dropLast(1).joinToString(".") + ".${last - 1}")
        }
    }
    return listOf("$bound.0")
}

private fun bumpLastNumericComponent(version: String): String {
    val parts = version.split(".")
    val last = parts.last().toIntOrNull() ?: return "$version.1"
    return parts.dropLast(1).joinToString(".") + ".${last + 1}"
}

private fun interiorProbes(left: String, right: String): List<String> {
    val leftParts = left.split(".")
    val rightParts = right.split(".")
    if (leftParts.size >= 2 && rightParts.size >= 2) {
        val leftMinor = leftParts.getOrNull(1)?.toIntOrNull()
        val rightMinor = rightParts.getOrNull(1)?.toIntOrNull()
        if (leftMinor != null && rightMinor != null && rightMinor - leftMinor >= 2) {
            return listOf("${leftParts[0]}.${leftMinor + 1}")
        }
    }
    return listOf("$left.5", "$left.1")
}
