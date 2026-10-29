package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.databind.JsonNode

/**
 * The set of component keys the **baseline** stand reports from `GET /rest/api/2/components`.
 *
 * This is the independent evidence behind [Adr021RangeRecovery]. A baseline twin only shows that an
 * element *could* have collapsed under the old equality contract; it says nothing about whether the
 * component ever existed. Copy the matching fields off a real element, give it a name, and the
 * collapse story fits perfectly — so the story cannot also be its own proof.
 *
 * The component inventory is that proof, because the collapse hides an element from the
 * `jira-component-version-ranges` **Set** and from nothing else: a component dropped there is still
 * listed by `/components`. Present in the baseline inventory and absent from the baseline ranges ⇒
 * recovered. Absent from both ⇒ invented, and the gate must say so.
 *
 * Unknown is not permission: while the inventory has not been loaded, [knows] answers `null` and the
 * rule refuses. Verification that cannot be performed is not verification that passed.
 */
object BaselineInventory {
    @Volatile
    private var componentIds: Set<String>? = null

    /**
     * Loads the inventory once per process. Subsequent calls are no-ops, so every suite may call it
     * from its `@BeforeAll` without coordinating. A `fetch` returning `null` (the call failed) leaves
     * the inventory unloaded rather than caching an empty set — an empty set would read as "no
     * component exists" and reject everything with a misleading reason.
     */
    fun ensureLoaded(fetch: () -> Set<String>?) {
        if (componentIds != null) return
        synchronized(this) {
            if (componentIds == null) componentIds = fetch()?.takeIf { it.isNotEmpty() }
        }
    }

    /** `true` / `false` once loaded, `null` while the inventory is unavailable. */
    fun knows(componentName: String): Boolean? = componentIds?.contains(componentName)

    /** Test seam: set the inventory directly, or clear it with `null`. */
    fun seed(values: Set<String>?) {
        componentIds = values
    }

    /** Extracts `components[].id` from a `GET /rest/api/2/components` body. */
    fun idsFrom(body: JsonNode?): Set<String>? {
        val components = body?.path("components")?.takeIf { it.isArray } ?: return null
        return components.mapNotNull { it.path("id").takeIf { id -> id.isTextual }?.asText() }.toSet()
    }
}
