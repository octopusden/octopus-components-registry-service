package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.model.ComponentProfile
import org.octopusden.octopus.components.registry.server.model.ProfileLoad
import org.octopusden.octopus.components.registry.server.util.ComponentProfileParser
import java.util.concurrent.atomic.AtomicReference

/**
 * The profiles in use, replaced only as a whole and only by a usable load (Decision 2).
 *
 * Construction loads once and throws [ComponentProfilesException] when the result is not usable,
 * so a context that creates this bean does not start without usable profiles (Decision 3).
 * [reload] reads again and returns its own outcome; an unusable or unreadable result leaves the
 * profiles in use untouched. The environment is read only here, never on a request.
 */
class ComponentProfileCatalog(
    private val read: () -> Map<String, String>,
) {
    private val current: AtomicReference<List<ComponentProfile>>

    init {
        val load = load()
        if (!load.usable) throw ComponentProfilesException(load)
        current = AtomicReference(load.profiles)
    }

    /** The live profiles, sorted by order then id. Take it once per request. */
    fun profiles(): List<ComponentProfile> = current.get()

    @Synchronized
    fun reload(): ProfileLoad {
        val load = load()
        if (load.usable) current.set(load.profiles)
        return load
    }

    private fun load(): ProfileLoad =
        runCatching { ComponentProfileParser.parse(read()) }
            .getOrElse { ProfileLoad(emptyList(), emptyList(), listOf("the configuration cannot be read: ${it.message}")) }
}

class ComponentProfilesException(
    val load: ProfileLoad,
) : IllegalStateException(
        (load.problems + load.entries.flatMap { it.problems })
            .joinToString(separator = "\n  - ", prefix = "Component profiles are not usable:\n  - "),
    )
