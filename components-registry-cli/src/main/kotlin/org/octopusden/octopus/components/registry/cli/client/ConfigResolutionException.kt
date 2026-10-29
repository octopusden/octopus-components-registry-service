package org.octopusden.octopus.components.registry.cli.client

/**
 * Thrown when the effective CRS target cannot be determined (no flag, env var, or resolvable
 * profile). Maps to [ExitCode.USAGE] — we never silently default to a wrong registry.
 */
class ConfigResolutionException(
    message: String,
) : RuntimeException(message)
