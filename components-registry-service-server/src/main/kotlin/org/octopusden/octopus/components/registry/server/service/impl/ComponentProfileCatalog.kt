package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.model.ComponentProfile
import org.octopusden.octopus.components.registry.server.model.ProfileLoad
import org.octopusden.octopus.components.registry.server.util.ComponentProfileParser
import org.octopusden.octopus.components.registry.server.template.ComponentTemplate
import java.util.concurrent.atomic.AtomicReference

/**
 * The profiles and templates in use, replaced together only as a whole and only by a usable load
 * (Decision 2; templates: Decision 1).
 *
 * Construction loads once and throws [ComponentProfilesException] when the result is not usable,
 * so a context that creates this bean does not start without usable profiles (Decision 3).
 * [reload] reads again and returns its own outcome; an unusable or unreadable result leaves what
 * is in use untouched. The environment is read only here, never on a request. [defaults] are the
 * `component-defaults` values by template field path, read on every load.
 */
class ComponentProfileCatalog(
    private val read: () -> Map<String, String>,
    private val defaults: () -> Map<String, String>,
) {
    private val current: AtomicReference<Snapshot>
    private val last: AtomicReference<ProfileLoad>

    init {
        val (load, raw) = load()
        if (!load.usable) throw ComponentProfilesException(load)
        current = AtomicReference(Snapshot(load, raw))
        last = AtomicReference(load)
    }

    constructor(read: () -> Map<String, String>) : this(read, { emptyMap() })

    /** The live profiles, sorted by order then id. Take it once per request. */
    fun profiles(): List<ComponentProfile> = current.get().profiles

    /** The live templates, sorted by order then id. */
    fun templates(): List<ComponentTemplate> = current.get().templates

    fun template(id: String): ComponentTemplate? = templates().firstOrNull { it.id == id }

    /** Every configured entry in use, live or failed, by id, with its keys as read (Decision 11). */
    fun rawEntries(): Map<String, Map<String, String>> = current.get().raw

    /** The entries in use, live or failed, sorted by id. */
    fun entries(): List<ProfileLoad.Entry> = current.get().entries

    /** The outcome of the last load or reload, applied or not. */
    fun lastLoad(): ProfileLoad = last.get()

    @Synchronized
    fun reload(): ProfileLoad {
        val (load, raw) = load()
        if (load.usable) current.set(Snapshot(load, raw))
        last.set(load)
        return load
    }

    private fun load(): Pair<ProfileLoad, Map<String, Map<String, String>>> =
        runCatching {
            val properties = read()
            ComponentProfileParser.parse(properties, defaults()) to byEntry(properties)
        }.getOrElse { ProfileLoad(emptyList(), emptyList(), listOf("the configuration cannot be read: ${it.message}")) to emptyMap() }

    private fun byEntry(properties: Map<String, String>): Map<String, Map<String, String>> =
        properties.entries
            .groupBy({ it.key.substringBefore('.') }, { it.key.substringAfter('.', "") to it.value })
            .mapValues { (_, keyValues) -> keyValues.toMap() }

    private class Snapshot(
        load: ProfileLoad,
        val raw: Map<String, Map<String, String>>,
    ) {
        val profiles = load.profiles
        val templates = load.templates
        val entries = load.entries
    }
}

class ComponentProfilesException(
    val load: ProfileLoad,
) : IllegalStateException(
        (load.problems + load.entries.flatMap { it.problems })
            .joinToString(separator = "\n  - ", prefix = "Component profiles are not usable:\n  - "),
    )
