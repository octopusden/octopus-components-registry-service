package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.dto.v4.AuditLogFilter
import org.octopusden.octopus.components.registry.server.dto.v4.AuditLogResponse
import org.octopusden.octopus.components.registry.server.entity.AuditLogEntity
import org.octopusden.octopus.components.registry.server.mapper.toResponse
import org.octopusden.octopus.components.registry.server.repository.AuditLogRepository
import org.octopusden.octopus.components.registry.server.service.AuditService
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
class AuditServiceImpl(
    private val auditLogRepository: AuditLogRepository,
) : AuditService {
    override fun getEntityHistory(
        entityType: String,
        entityId: String,
        includeMigrated: Boolean,
        pageable: Pageable,
    ): Page<AuditLogResponse> =
        if (includeMigrated) {
            auditLogRepository.findByEntityTypeAndEntityId(entityType, entityId, pageable)
        } else {
            // Default: hide git-history baseline noise (action = MIGRATED). SYS-049.
            auditLogRepository.findByEntityTypeAndEntityIdAndActionNot(
                entityType,
                entityId,
                AuditLogEntity.ACTION_MIGRATED,
                pageable,
            )
        }.map { it.toResponse() }

    override fun getRecentChanges(
        filter: AuditLogFilter,
        pageable: Pageable,
    ): Page<AuditLogResponse> =
        auditLogRepository
            .findAll(buildSpecification(filter), withDefaultSort(pageable))
            .map { it.toResponse() }

    /**
     * `audit_log` rows are most useful "newest first" by `changed_at`. The legacy
     * `findAllByOrderByChangedAtDesc` enforced this in the query name; with the
     * Specification-based path we apply the same default in the Pageable when the
     * caller hasn't supplied an explicit sort, so existing clients keep their ordering.
     */
    private fun withDefaultSort(pageable: Pageable): Pageable =
        if (pageable.sort.isUnsorted) {
            PageRequest.of(pageable.pageNumber, pageable.pageSize, Sort.by(Sort.Direction.DESC, "changedAt"))
        } else {
            pageable
        }

    private fun buildSpecification(filter: AuditLogFilter): Specification<AuditLogEntity> {
        var spec = Specification.where<AuditLogEntity>(null)

        filter.entityType?.let { entityType ->
            spec = spec.and(Specification { root, _, cb -> cb.equal(root.get<String>("entityType"), entityType) })
        }

        filter.entityId?.let { entityId ->
            spec = spec.and(Specification { root, _, cb -> cb.equal(root.get<String>("entityId"), entityId) })
        }

        filter.changedBy?.let { changedBy ->
            spec = spec.and(Specification { root, _, cb -> cb.equal(root.get<String>("changedBy"), changedBy) })
        }

        filter.source?.let { source ->
            spec = spec.and(Specification { root, _, cb -> cb.equal(root.get<String>("source"), source) })
        }

        filter.action?.let { action ->
            spec = spec.and(Specification { root, _, cb -> cb.equal(root.get<String>("action"), action) })
        }

        // Hide git-history baseline rows (action = MIGRATED) unless the caller opted
        // in via includeMigrated, or pinned them with an explicit action filter
        // (which the equal-predicate above already enforces). SYS-049.
        if (!filter.includeMigrated && filter.action == null) {
            spec =
                spec.and(
                    Specification { root, _, cb ->
                        cb.notEqual(root.get<String>("action"), AuditLogEntity.ACTION_MIGRATED)
                    },
                )
        }

        filter.from?.let { from ->
            spec =
                spec.and(
                    Specification { root, _, cb -> cb.greaterThanOrEqualTo(root.get<Instant>("changedAt"), from) },
                )
        }

        filter.to?.let { to ->
            spec = spec.and(Specification { root, _, cb -> cb.lessThan(root.get<Instant>("changedAt"), to) })
        }

        return spec
    }
}
