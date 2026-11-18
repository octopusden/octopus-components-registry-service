package org.octopusden.octopus.components.registry.server.service

/**
 * SYS-066 — thrown to ABORT the composite field-override split (rolling back the whole transaction)
 * when it cannot proceed safely: a composite on an unexpected attribute, self-overlapping segments,
 * a sibling collision whose payload is not provably identical, a malformed range, or a
 * stale/missing manifest token on a write. Mapped to HTTP 409 by the admin controller.
 */
class CompositeOverrideSplitAbortedException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
