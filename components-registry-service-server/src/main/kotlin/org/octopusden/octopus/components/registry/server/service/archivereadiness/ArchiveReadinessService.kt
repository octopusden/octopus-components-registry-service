package org.octopusden.octopus.components.registry.server.service.archivereadiness

import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.dto.v4.ArchiveReadinessResponse
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.springframework.stereotype.Service
import java.util.UUID

// SYS-047: injects a JPA repository directly (and ArchiveReadinessAssembler, itself DB-only),
// so it must be dropped in no-db mode too — see ConditionalOnDatabaseEnabled's kdoc ("or
// another bean so annotated"). ComponentControllerV4 is already class-level
// @ConditionalOnDatabaseEnabled, so this mirrors the caller's own gate.

/**
 * Resolves the `{id}` path segment of `GET .../components/{id}/archive-readiness` (UUID or
 * component name, mirroring [org.octopusden.octopus.components.registry.server.controller.ComponentControllerV4.getComponent])
 * and delegates the actual assembly to [ArchiveReadinessAssembler].
 */
@ConditionalOnDatabaseEnabled
@Service
class ArchiveReadinessService(
    private val assembler: ArchiveReadinessAssembler,
    private val componentRepository: ComponentRepository,
) {
    fun getReadiness(idOrName: String): ArchiveReadinessResponse {
        val component = resolveComponent(idOrName)
        return assembler.assemble(component)
    }

    // `findById(uuid).orElse(null)` — NOT `orElseThrow` — is what lets a UUID that parses but
    // doesn't exist as a real component id fall through to the name lookup below. A component
    // whose NAME happens to parse as a UUID must still resolve by name (mirrors getComponent's
    // id-or-name contract, SYS-028).
    private fun resolveComponent(idOrName: String) =
        runCatching { UUID.fromString(idOrName) }.getOrNull()?.let { uuid ->
            componentRepository.findById(uuid).orElse(null)
        } ?: componentRepository.findByComponentKey(idOrName)
            ?: throw NotFoundException("Component '$idOrName' not found")
}
