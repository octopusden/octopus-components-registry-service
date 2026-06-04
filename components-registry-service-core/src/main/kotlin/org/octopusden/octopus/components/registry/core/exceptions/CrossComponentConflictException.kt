package org.octopusden.octopus.components.registry.core.exceptions

/**
 * Raised when a v4 write would make a component collide with ANOTHER component
 * on a registry-wide-unique coordinate — duplicate `groupId:artifactId` in
 * overlapping version ranges, a shared jira `(projectKey, versionPrefix)` among
 * non-archived components, or a globally-duplicated docker image name. These are
 * cross-component integrity rules the old `EscrowConfigValidator` enforced at
 * config-load time; the v4 API restores them at write time.
 *
 * Mapped to HTTP 409 Conflict by `ControllerExceptionHandler`, alongside
 * [ComponentNameConflictException]. Malformed-input checks (e.g. a missing
 * distribution coordinate, an unsupported groupId prefix) stay 400 and use
 * `IllegalArgumentException`, not this type — a conflict means "valid input that
 * clashes with existing data", not "bad input".
 */
class CrossComponentConflictException(
    message: String,
) : BaseComponentsRegistryException(message)
