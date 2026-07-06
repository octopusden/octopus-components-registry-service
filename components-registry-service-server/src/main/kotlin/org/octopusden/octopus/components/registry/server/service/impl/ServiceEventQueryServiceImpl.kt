package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.dto.v4.ServiceEventFilter
import org.octopusden.octopus.components.registry.server.dto.v4.ServiceEventResponse
import org.octopusden.octopus.components.registry.server.entity.ServiceEventEntity
import org.octopusden.octopus.components.registry.server.repository.ServiceEventRepository
import org.octopusden.octopus.components.registry.server.service.ServiceEventQueryService
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@ConditionalOnDatabaseEnabled
@Service
@Transactional(readOnly = true)
class ServiceEventQueryServiceImpl(
    private val serviceEventRepository: ServiceEventRepository,
) : ServiceEventQueryService {
    override fun find(
        filter: ServiceEventFilter,
        pageable: Pageable,
    ): Page<ServiceEventResponse> =
        serviceEventRepository
            .findAll(buildSpecification(filter), withDefaultSort(pageable))
            .map(ServiceEventResponse::from)

    // The journal is a timeline: newest first by started_at unless the caller asked otherwise.
    private fun withDefaultSort(pageable: Pageable): Pageable =
        if (pageable.sort.isUnsorted) {
            PageRequest.of(pageable.pageNumber, pageable.pageSize, Sort.by(Sort.Direction.DESC, "startedAt"))
        } else {
            pageable
        }

    private fun buildSpecification(filter: ServiceEventFilter): Specification<ServiceEventEntity> {
        var spec = Specification.where<ServiceEventEntity>(null)

        filter.eventType?.takeIf { it.isNotBlank() }?.let { eventType ->
            // Case-insensitive, mirroring the source/status filters below: stored values are the
            // uppercase enum name(), so a caller passing `startup` / `teamcity_resync` still matches.
            spec = spec.and(Specification { root, _, cb -> cb.equal(cb.upper(root.get("eventType")), eventType.uppercase()) })
        }
        filter.source?.takeIf { it.isNotBlank() }?.let { source ->
            // Match the stored lowercase wire form regardless of caller casing.
            spec = spec.and(Specification { root, _, cb -> cb.equal(cb.lower(root.get("source")), source.lowercase()) })
        }
        filter.status?.takeIf { it.isNotBlank() }?.let { status ->
            spec = spec.and(Specification { root, _, cb -> cb.equal(root.get<String>("status"), status.uppercase()) })
        }
        filter.from?.let { from ->
            spec = spec.and(Specification { root, _, cb -> cb.greaterThanOrEqualTo(root.get<Instant>("startedAt"), from) })
        }
        filter.to?.let { to ->
            spec = spec.and(Specification { root, _, cb -> cb.lessThan(root.get<Instant>("startedAt"), to) })
        }
        return spec
    }
}
