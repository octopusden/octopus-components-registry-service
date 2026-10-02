package org.octopusden.octopus.components.registry.server.service

import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.dto.v4.DistributionPreviewRequest
import org.octopusden.octopus.components.registry.server.dto.v4.DistributionPreviewResponse
import org.octopusden.octopus.components.registry.server.mapper.previewDistribution
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionRangeFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** SYS-099: read-only distribution preview; the evaluation itself is [previewDistribution]. */
@ConditionalOnDatabaseEnabled
@Service
class DistributionPreviewService(
    private val componentRepository: ComponentRepository,
    private val versionRangeFactory: VersionRangeFactory,
    private val numericVersionFactory: NumericVersionFactory,
) {
    @Transactional(readOnly = true)
    fun preview(
        idOrName: String,
        request: DistributionPreviewRequest,
    ): DistributionPreviewResponse = resolveComponent(idOrName).previewDistribution(request, versionRangeFactory, numericVersionFactory)

    // Same id-or-name contract as getComponent (SYS-028): a UUID that matches no id falls through to
    // the name lookup, so a component whose name parses as a UUID still resolves.
    private fun resolveComponent(idOrName: String) =
        runCatching { UUID.fromString(idOrName) }.getOrNull()?.let { uuid ->
            componentRepository.findById(uuid).orElse(null)
        } ?: componentRepository.findByComponentKey(idOrName)
            ?: throw NotFoundException("Component '$idOrName' not found")
}
