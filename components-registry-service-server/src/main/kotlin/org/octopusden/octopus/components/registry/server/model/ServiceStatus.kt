package org.octopusden.octopus.components.registry.server.model

import org.octopusden.octopus.components.registry.core.dto.ServiceMode
import java.util.*

data class ServiceStatus(val serviceMode: ServiceMode, var cacheUpdatedAt: Date, var versionControlRevision: String? = null)
