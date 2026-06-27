package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.dto.v4.AuditLogFilter
import org.octopusden.octopus.components.registry.server.dto.v4.AuditLogResponse
import org.octopusden.octopus.components.registry.server.entity.AuditLogEntity
import org.octopusden.octopus.components.registry.server.mapper.toResponse
import org.octopusden.octopus.components.registry.server.repository.AuditLogRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.service.AuditService
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@ConditionalOnDatabaseEnabled
@Service
@Transactional(readOnly = true)
class AuditServiceImpl(
    private val auditLogRepository: AuditLogRepository,
    private val componentRepository: ComponentRepository,
) : AuditService {
    override fun getEntityHistory(
        entityType: String,
        entityId: String,
        includeMigrated: Boolean,
        pageable: Pageable,
    ): Page<AuditLogResponse> {
        // Apply the same "newest first" default as getRecentChanges when the
        // caller supplies no explicit sort — entity history is a timeline.
        val sorted = withDefaultSort(pageable)
        return if (includeMigrated) {
            auditLogRepository.findByEntityTypeAndEntityId(entityType, entityId, sorted)
        } else {
            // Default: hide git-history baseline noise (action = MIGRATED). SYS-049.
            auditLogRepository.findByEntityTypeAndEntityIdAndActionNot(
                entityType,
                entityId,
                AuditLogEntity.ACTION_MIGRATED,
                sorted,
            )
        }.toResponsePage()
    }

    override fun getRecentChanges(
        filter: AuditLogFilter,
        pageable: Pageable,
    ): Page<AuditLogResponse> =
        auditLogRepository
            .findAll(buildSpecification(filter), withDefaultSort(pageable))
            .toResponsePage()

    /**
     * Map a page of audit rows to responses, resolving each Component row's
     * human-readable componentKey. Resolution is batched: one findAllById over
     * the page's distinct component UUIDs, not a query per row.
     */
    private fun Page<AuditLogEntity>.toResponsePage(): Page<AuditLogResponse> {
        val liveKeys = resolveLiveComponentKeys(content)
        return map { it.toResponse(it.resolveComponentKey(liveKeys)) }
    }

    /** entityId UUID -> current componentKey, for the Component rows on a page. */
    private fun resolveLiveComponentKeys(entities: List<AuditLogEntity>): Map<UUID, String> {
        val ids =
            entities
                .filter { it.entityType == ENTITY_TYPE_COMPONENT }
                .mapNotNull { it.entityId.toUuidOrNull() }
                .distinct()
        if (ids.isEmpty()) return emptyMap()
        return componentRepository.findAllById(ids).associate { it.id!! to it.componentKey }
    }

    private fun AuditLogEntity.resolveComponentKey(liveKeys: Map<UUID, String>): String? {
        if (entityType != ENTITY_TYPE_COMPONENT) return null
        // Live key = the component's CURRENT key. On RENAME rows this is the new
        // (post-rename) key by design: the log shows each row under the
        // component's present identity, and the old key stays visible in oldValue.
        entityId.toUuidOrNull()?.let { liveKeys[it] }?.let { return it }
        // Component no longer exists (DELETE / MIGRATED / hard-deleted): fall
        // back to the human-readable name captured in the snapshot. Component
        // CRUD snapshots use `name`; git-history (MIGRATED) snapshots `moduleName`.
        // takeIf{isNotBlank} keeps the null contract honest if a snapshot ever
        // carried a blank name (the response advertises null for "unresolvable").
        return (
            newValue?.get("name")
                ?: oldValue?.get("name")
                ?: newValue?.get("moduleName")
                ?: oldValue?.get("moduleName")
        ).let { it as? String }?.takeIf { it.isNotBlank() }
    }

    private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

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

        filter.jiraTaskKey?.let { jiraTaskKey ->
            spec = spec.and(Specification { root, _, cb -> cb.equal(root.get<String>("jiraTaskKey"), jiraTaskKey) })
        }

        return spec
    }

    companion object {
        // The entityType audit rows carry for component changes (component CRUD,
        // section edits, field overrides and git-history MIGRATED all share it).
        private const val ENTITY_TYPE_COMPONENT = "Component"
    }
}
