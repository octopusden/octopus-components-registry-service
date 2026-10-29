package org.octopusden.octopus.components.registry.cli.client

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Builds an URL query string from a sequence of name/value pairs, omitting any pair whose value is
 * null. Multi-valued params (e.g. Spring's repeated `sort`) are expressed as multiple pairs sharing
 * the same name and are emitted in insertion order.
 *
 * Both names and values are percent-encoded. The returned string has NO leading `?`; callers join it
 * onto a path themselves (see [CrsClient]).
 */
class QueryParams private constructor(
    private val pairs: List<Pair<String, String>>,
) {
    fun isEmpty(): Boolean = pairs.isEmpty()

    /** Renders the query string without a leading `?`, or an empty string when there are no params. */
    fun encode(): String =
        pairs.joinToString("&") { (name, value) ->
            "${enc(name)}=${enc(value)}"
        }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    class Builder {
        private val pairs = mutableListOf<Pair<String, String>>()

        /** Adds a single param unless [value] is null. Non-String values use their `toString()`. */
        fun add(
            name: String,
            value: Any?,
        ): Builder {
            if (value != null) {
                pairs += name to value.toString()
            }
            return this
        }

        /** Adds one entry per non-null element of [values]; a null list is skipped entirely. */
        fun addAll(
            name: String,
            values: List<String>?,
        ): Builder {
            values?.forEach { pairs += name to it }
            return this
        }

        /**
         * Adds the standard Spring pageable params. `page`/`size` are added only when non-null;
         * each entry of `sort` becomes its own `sort=` param (Spring's repeated-param convention).
         */
        fun pageable(
            page: Int? = null,
            size: Int? = null,
            sort: List<String>? = null,
        ): Builder {
            add("page", page)
            add("size", size)
            addAll("sort", sort)
            return this
        }

        /** Adds arbitrary filter params, skipping null values. */
        fun filters(filters: Map<String, Any?>): Builder {
            filters.forEach { (name, value) -> add(name, value) }
            return this
        }

        fun build(): QueryParams = QueryParams(pairs.toList())
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}
