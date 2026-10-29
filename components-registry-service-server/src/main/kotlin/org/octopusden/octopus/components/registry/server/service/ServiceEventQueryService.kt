package org.octopusden.octopus.components.registry.server.service

import org.octopusden.octopus.components.registry.server.dto.v4.ServiceEventFilter
import org.octopusden.octopus.components.registry.server.dto.v4.ServiceEventResponse
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

/** SYS-060: read side of the service-event journal. */
interface ServiceEventQueryService {
    /** Paginated events matching [filter], newest first by `started_at` when unsorted. */
    fun find(
        filter: ServiceEventFilter,
        pageable: Pageable,
    ): Page<ServiceEventResponse>
}
