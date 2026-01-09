package org.drivine.mapper

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date
import java.util.UUID

/**
 * Provides a centralized, Neo4j-aware ObjectMapper configuration for Drivine.
 *
 * This ObjectMapper is configured to:
 * - Serialize types to Neo4j-compatible formats (Enum -> String, UUID -> String, Instant -> ZonedDateTime)
 * - Include null values when serializing (allows explicitly removing properties with `SET p += $props`)
 * - Ignore unknown properties when deserializing (for graph evolution)
 * - Handle Java 8 time types appropriately
 * - Support Kotlin data classes
 *
 * To exclude nulls on specific properties, use: `@JsonInclude(JsonInclude.Include.NON_NULL)`
 * To exclude nulls on a whole class, annotate the class with the same annotation.
 */
object Neo4jObjectMapper {

    /**
     * Returns a shared, thread-safe ObjectMapper instance configured for Neo4j.
     * This mapper is used by both TransformPostProcessor (reading from graph)
     * and can be used for binding objects to queries (writing to graph).
     */
    val instance: ObjectMapper by lazy {
        jacksonObjectMapper().apply {
            // Deserialization configuration - ignore unknown properties for graph evolution
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

            // Serialization configuration - Neo4j-compatible typesxD
            registerModule(JavaTimeModule())
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

            // Include null values when serializing (allows explicitly removing properties)
            // Users can exclude nulls on specific properties with @JsonInclude(JsonInclude.Include.NON_NULL)
            setSerializationInclusion(JsonInclude.Include.ALWAYS)

            // Register custom serializers for Neo4j-specific conversions
            registerModule(SimpleModule().apply {
                // Enum -> String serializer
                addSerializer(Enum::class.java, EnumToStringSerializer())

                // UUID -> String serializer
                addSerializer(UUID::class.java, UuidToStringSerializer())

                // Instant -> ZonedDateTime serializer (Neo4j doesn't support Instant directly)
                addSerializer(Instant::class.java, InstantToZonedDateTimeSerializer())

                // Date -> ZonedDateTime serializer (legacy java.util.Date support)
                addSerializer(Date::class.java, DateToZonedDateTimeSerializer())
            })
        }
    }

    /**
     * Serializes Enum values to their string names for Neo4j compatibility.
     */
    private class EnumToStringSerializer : JsonSerializer<Enum<*>>() {
        override fun serialize(value: Enum<*>, gen: JsonGenerator, serializers: SerializerProvider) {
            gen.writeString(value.name)
        }
    }

    /**
     * Serializes UUID to String for Neo4j compatibility.
     */
    private class UuidToStringSerializer : JsonSerializer<UUID>() {
        override fun serialize(value: UUID, gen: JsonGenerator, serializers: SerializerProvider) {
            gen.writeString(value.toString())
        }
    }

    /**
     * Serializes Instant to ZonedDateTime at UTC for Neo4j compatibility.
     * Neo4j's driver supports ZonedDateTime but not Instant directly.
     */
    private class InstantToZonedDateTimeSerializer : JsonSerializer<Instant>() {
        override fun serialize(value: Instant, gen: JsonGenerator, serializers: SerializerProvider) {
            val zonedDateTime = value.atZone(ZoneId.of("UTC"))
            gen.writeObject(zonedDateTime)
        }
    }

    /**
     * Serializes legacy java.util.Date to ZonedDateTime at UTC for Neo4j compatibility.
     */
    private class DateToZonedDateTimeSerializer : JsonSerializer<Date>() {
        override fun serialize(value: Date, gen: JsonGenerator, serializers: SerializerProvider) {
            val zonedDateTime = value.toInstant().atZone(ZoneId.of("UTC"))
            gen.writeObject(zonedDateTime)
        }
    }
}

/**
 * Converts an object to a Map<String, Any?> using Neo4j-aware serialization.
 *
 * This extension function provides a type-safe way to convert objects to maps
 * without requiring @Suppress("UNCHECKED_CAST") at call sites.
 *
 * Example:
 * ```kotlin
 * val person = Person(uuid = UUID.randomUUID(), name = "Alice", createdAt = Instant.now())
 * val props = Neo4jObjectMapper.instance.toMap(person)
 * // props now contains: {uuid: "...", name: "Alice", createdAt: ZonedDateTime}
 * ```
 */
@Suppress("UNCHECKED_CAST")
fun ObjectMapper.toMap(value: Any): Map<String, Any?> {
    return this.convertValue(value, Map::class.java) as Map<String, Any?>
}

/**
 * Converts a single value to a Neo4j-compatible format.
 *
 * This function handles type conversions for values that Neo4j's driver doesn't support directly:
 * - Instant → ZonedDateTime (at UTC)
 * - UUID → String
 * - Enum → String (enum name)
 * - Date → ZonedDateTime (at UTC)
 * - Collections are recursively converted
 * - Maps are recursively converted
 * - Complex objects are converted via Jackson toMap()
 *
 * Primitives (String, Number, Boolean) and Neo4j-native temporal types
 * (ZonedDateTime, LocalDate, LocalDateTime) pass through unchanged.
 */
fun ObjectMapper.convertValueForNeo4j(value: Any?): Any? {
    if (value == null) return null
    return when (value) {
        // Primitives that Neo4j supports directly
        is String, is Number, is Boolean -> value
        // Convert Instant to ZonedDateTime (Neo4j doesn't support Instant)
        is Instant -> value.atZone(ZoneId.of("UTC"))
        // Convert UUID to String
        is UUID -> value.toString()
        // Convert Enum to String
        is Enum<*> -> value.name
        // Convert legacy Date to ZonedDateTime
        is Date -> value.toInstant().atZone(ZoneId.of("UTC"))
        // Keep ZonedDateTime, LocalDate, LocalDateTime as-is (Neo4j supports them)
        is ZonedDateTime, is java.time.LocalDate, is java.time.LocalDateTime, is java.time.LocalTime -> value
        // Recursively handle collections
        is Collection<*> -> value.map { convertValueForNeo4j(it) }
        // Handle arrays (convert to list and recursively process)
        is Array<*> -> value.map { convertValueForNeo4j(it) }
        is IntArray -> value.toList()
        is LongArray -> value.toList()
        is DoubleArray -> value.toList()
        is FloatArray -> value.toList()
        is BooleanArray -> value.toList()
        is ByteArray -> value.toList()
        is ShortArray -> value.toList()
        is CharArray -> value.toList()
        // Recursively handle maps
        is Map<*, *> -> value.mapValues { (_, v) -> convertValueForNeo4j(v) }
        // For complex objects, use Jackson toMap() then recursively convert
        else -> toMap(value).mapValues { (_, v) -> convertValueForNeo4j(v) }
    }
}
