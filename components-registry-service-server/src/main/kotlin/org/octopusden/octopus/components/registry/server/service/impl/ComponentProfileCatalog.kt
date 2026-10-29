package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.model.ComponentProfile
import org.octopusden.octopus.components.registry.server.model.ComponentTemplate
import org.octopusden.octopus.components.registry.server.model.ProfileLoad
import org.octopusden.octopus.components.registry.server.util.ComponentProfileParser
import java.util.concurrent.atomic.AtomicReference

/**
 * The profiles and templates in use, replaced together only as a whole and only by a usable load
 * (Decision 2; templates: Decision 1).
 *
 * Construction loads once and throws [ComponentProfilesException] when the result is not usable,
 * so a context that creates this bean does not start with a broken profile subtree (Decision 3); an
 * absent subtree is usable and leaves the catalog empty. [reload] reads again and returns its own
 * outcome; an unusable or unreadable result leaves what is in use untouched, while a removed subtree
 * applies no profiles and no templates. The environment is read only here, never on a request.
 * [defaults] are the `component-defaults` values by template field path, read on every load.
 * [version] is the configuration's version, read with it, so the version shown is the one the
 * entries in use came from.
 */
class ComponentProfileCatalog(
    private val read: () -> Map<String, String>,
    private val defaults: () -> Map<String, String>,
    private val version: () -> String? = { null },
) {
    private val current: AtomicReference<Snapshot>

    init {
        val (load, raw, version) = load()
        if (!load.usable) throw ComponentProfilesException(load)
        current = AtomicReference(Snapshot(load, raw, version, load))
    }

    constructor(read: () -> Map<String, String>) : this(read, { emptyMap() })

    /**
     * Everything in use from one load, with the outcome of the last load or reload. A response that
     * reads more than one part takes it once, so a reload in between cannot mix revisions.
     */
    fun snapshot(): Snapshot = current.get()

    /** The live profiles, sorted by order then id. Take it once per request. */
    fun profiles(): List<ComponentProfile> = current.get().profiles

    /** The live templates, sorted by order then id. */
    fun templates(): List<ComponentTemplate> = current.get().templates

    fun template(id: String): ComponentTemplate? = templates().firstOrNull { it.id == id }

    /** The outcome of the last load or reload, applied or not. */
    fun lastLoad(): ProfileLoad = current.get().lastLoad

    @Synchronized
    fun reload(): ProfileLoad {
        val (load, raw, version) = load()
        current.set(if (load.usable) Snapshot(load, raw, version, load) else current.get().withLastLoad(load))
        return load
    }

    private fun load(): Triple<ProfileLoad, Map<String, Map<String, String>>, String?> =
        runCatching {
            val properties = read()
            Triple(ComponentProfileParser.parse(properties, defaults()), byEntry(properties), version())
        }.getOrElse {
            Triple(ProfileLoad(emptyList(), emptyList(), listOf("the configuration cannot be read: ${it.message}")), emptyMap(), null)
        }

    private fun byEntry(properties: Map<String, String>): Map<String, Map<String, String>> =
        properties.entries
            .groupBy({ it.key.substringBefore('.') }, { it.key.substringAfter('.', "") to it.value })
            .mapValues { (_, keyValues) -> keyValues.toMap() }

    /**
     * [raw] holds every configured entry's keys as read, by id, and [configVersion] the version they
     * were read from. [lastLoad] is the outcome of the last load or reload: the one in use, or a
     * later one that was not usable and so left the rest as is.
     */
    class Snapshot(
        private val inUse: ProfileLoad,
        val raw: Map<String, Map<String, String>>,
        val configVersion: String?,
        val lastLoad: ProfileLoad,
    ) {
        val profiles: List<ComponentProfile> = inUse.profiles
        val templates: List<ComponentTemplate> = inUse.templates
        val entries: List<ProfileLoad.Entry> = inUse.entries

        internal fun withLastLoad(load: ProfileLoad) = Snapshot(inUse, raw, configVersion, load)
    }
}

class ComponentProfilesException(
    val load: ProfileLoad,
) : IllegalStateException(
        (load.problems + load.entries.flatMap { it.problems })
            .joinToString(separator = "\n  - ", prefix = "Component profiles are not usable:\n  - "),
    )
