package org.octopusden.octopus.components.registry.server.config

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import org.hibernate.type.descriptor.WrapperOptions
import org.hibernate.type.descriptor.java.JavaType
import org.hibernate.type.format.FormatMapper

/**
 * Drop-in replacement for Hibernate 6.4's `JacksonJsonFormatMapper` that safely handles
 * entity fields declared as `Any?` (i.e. `Object.class`) when mapped to a `jsonb` /
 * `SqlTypes.JSON` column.
 *
 * The stock mapper short-circuits when `javaType.javaType == Object.class` with an unsafe
 * `(String) value` cast (see `org.hibernate.type.format.jackson.JacksonJsonFormatMapper`
 * in hibernate-core 6.4.1.Final, lines 41, 53). That cast throws `ClassCastException`
 * for any non-String value (e.g. `LinkedHashMap`), surfacing as a
 * `TransactionSystemException: Could not commit JPA transaction` at write time. It bites
 * our `FieldOverrideEntity.value: Any?` field and would bite any future `Any?` / `Object`
 * jsonb field the same way, on every dialect — this is not H2-specific.
 *
 * This mapper always routes serialization through Jackson, which handles arbitrary Java
 * objects correctly. Registered via `hibernate.type.json_format_mapper` so Hibernate
 * picks it up in place of the default.
 *
 * @see SYS-027
 */
@Suppress("UNCHECKED_CAST")
class SafeJsonFormatMapper(
    private val objectMapper: ObjectMapper = ObjectMapper().findAndRegisterModules(),
) : FormatMapper {
    override fun <T> fromString(
        charSequence: CharSequence,
        javaType: JavaType<T>,
        wrapperOptions: WrapperOptions,
    ): T = readJson(charSequence, javaType)

    override fun <T> toString(
        value: T,
        javaType: JavaType<T>,
        wrapperOptions: WrapperOptions,
    ): String = writeJson(value, javaType)

    private fun <T> readJson(
        charSequence: CharSequence,
        javaType: JavaType<T>,
    ): T {
        // Only String is short-circuited; no entity in this project declares a
        // jsonb column as CharSequence / ByteArray / ByteBuffer. If one is added,
        // mirror the stock Hibernate mapper's extra fast-paths here before the
        // Jackson fallback silently wraps them.
        if (javaType.javaType === String::class.java) {
            return charSequence.toString() as T
        }
        return try {
            objectMapper.readValue(
                charSequence.toString(),
                objectMapper.constructType(javaType.javaType),
            )
        } catch (e: JsonProcessingException) {
            throw IllegalArgumentException("Could not deserialize string to java type: $javaType", e)
        }
    }

    private fun <T> writeJson(
        value: T,
        javaType: JavaType<T>,
    ): String {
        if (javaType.javaType === String::class.java) {
            return value as String
        }
        return try {
            objectMapper
                .writerFor(objectMapper.constructType(javaType.javaType))
                .writeValueAsString(value)
        } catch (e: JsonProcessingException) {
            throw IllegalArgumentException("Could not serialize object of java type: $javaType", e)
        }
    }
}
