package org.drivine.mapper

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.introspect.AnnotationIntrospectorPair
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.util.TokenBuffer
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
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

            // Serialization configuration - Neo4j-compatible types
            registerModule(JavaTimeModule())
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

            // Include null values when serializing (allows explicitly removing properties)
            // Users can exclude nulls on specific properties with @JsonInclude(JsonInclude.Include.NON_NULL)
            setSerializationInclusion(JsonInclude.Include.ALWAYS)

            // Use custom visibility checker that makes private fields with Drivine annotations visible.
            // This allows the private backing field pattern with explicit Jackson annotations.
            setVisibility(visibilityChecker.withDrivineAnnotationSupport())

            // Add custom annotation introspector to auto-ignore Kotlin delegate backing fields.
            // This eliminates the need for @JsonIgnoreProperties("prop$delegate") on classes
            // that use lazy properties or other Kotlin delegates.
            setAnnotationIntrospector(
                AnnotationIntrospectorPair.pair(
                    DrivineAnnotationIntrospector(),
                    serializationConfig.annotationIntrospector
                )
            )

            // @JsonPacked support — serialize/deserialize collections as JSON strings
            registerModule(JsonPackedModule())

            // Register custom serializers for Neo4j-specific conversions.
            // Note: temporals are intentionally NOT customized here — this mapper handles reads
            // (result map → domain) and real JSON (@JsonPacked), where JavaTimeModule's ISO-string
            // form is correct. The object→property-map *write* path uses a separate write mapper that
            // preserves native temporals (see [toMap] / [temporalWriteMapper]).
            registerModule(SimpleModule().apply {
                addSerializer(Enum::class.java, EnumToStringSerializer())
                addSerializer(UUID::class.java, UuidToStringSerializer())
            })
        }
    }

    /**
     * Serializes Enum values to their string names for Neo4j compatibility.
     */
    internal class EnumToStringSerializer : JsonSerializer<Enum<*>>() {
        override fun serialize(value: Enum<*>, gen: JsonGenerator, serializers: SerializerProvider) {
            gen.writeString(value.name)
        }
    }

    /**
     * Serializes UUID to String for Neo4j compatibility.
     */
    internal class UuidToStringSerializer : JsonSerializer<UUID>() {
        override fun serialize(value: UUID, gen: JsonGenerator, serializers: SerializerProvider) {
            gen.writeString(value.toString())
        }
    }

    /**
     * Serializes a value as an *embedded object* when going through a Jackson [TokenBuffer] (the
     * `convertValue` / [toMap] object→map path), so the native Java value survives the round-trip
     * instead of being stringified. Used only by the [temporalWriteMapper]; never used to write
     * real JSON (the fallback is defensive).
     */
    internal class EmbeddedObjectSerializer<T : Any> : JsonSerializer<T>() {
        override fun serialize(value: T, gen: JsonGenerator, serializers: SerializerProvider) {
            if (gen is TokenBuffer) gen.writeEmbeddedObject(value) else gen.writeString(value.toString())
        }
    }
}

/**
 * A Jackson module that preserves temporal values as their native java.time types when converting
 * an object to a property map (instead of JavaTimeModule's ISO strings). Registered only on the
 * write mapper used by [toMap] — see that function for why writes and reads use different mappers.
 */
internal class Neo4jTemporalWriteModule : SimpleModule() {
    init {
        addSerializer(Instant::class.java, Neo4jObjectMapper.EmbeddedObjectSerializer())
        addSerializer(Date::class.java, Neo4jObjectMapper.EmbeddedObjectSerializer())
        addSerializer(ZonedDateTime::class.java, Neo4jObjectMapper.EmbeddedObjectSerializer())
        addSerializer(OffsetDateTime::class.java, Neo4jObjectMapper.EmbeddedObjectSerializer())
        addSerializer(LocalDate::class.java, Neo4jObjectMapper.EmbeddedObjectSerializer())
        addSerializer(LocalDateTime::class.java, Neo4jObjectMapper.EmbeddedObjectSerializer())
        addSerializer(LocalTime::class.java, Neo4jObjectMapper.EmbeddedObjectSerializer())
    }
}

/** Write mappers, derived per source mapper, that preserve native temporals (see [toMap]). */
private val temporalWriteMappers = java.util.concurrent.ConcurrentHashMap<ObjectMapper, ObjectMapper>()

/** The temporal-preserving write variant of [this] (cached); shares all other configuration. */
private fun ObjectMapper.temporalWriteMapper(): ObjectMapper =
    temporalWriteMappers.getOrPut(this) { copy().registerModule(Neo4jTemporalWriteModule()) }

/**
 * Converts an object to a `Map<String, Any?>` of its properties for use as Neo4j query parameters.
 *
 * Temporal values are kept as their **native** `java.time` types (not ISO strings): the map then
 * flows through `.bind()` → [convertValueForNeo4j] → the connection's parameter coercers, which is
 * exactly the pipeline bound query params use — so a saved `Instant` is stored as a native datetime
 * (or coerced to a string per backend), identically to a param. If [toMap] stringified temporals,
 * that downstream pipeline would only ever see a dead string, and `WHERE n.ts >= $param` would
 * silently match nothing (Cypher returns null comparing String to a datetime).
 *
 * This requires a *different* serialization than reads: the result-map → domain conversion also uses
 * `convertValue`, where a native temporal must be deserialized back into the field's type (e.g. a
 * stored `ZonedDateTime` into an `Instant` field) — which only works via ISO strings. So reads use
 * the main mapper (JavaTimeModule strings) and writes use a temporal-preserving variant.
 *
 * ```kotlin
 * val person = Person(uuid = UUID.randomUUID(), name = "Alice", createdAt = Instant.now())
 * val props = Neo4jObjectMapper.instance.toMap(person)
 * // props now contains: {uuid: "...", name: "Alice", createdAt: Instant}
 * ```
 */
@Suppress("UNCHECKED_CAST")
fun ObjectMapper.toMap(value: Any): Map<String, Any?> {
    return temporalWriteMapper().convertValue(value, Map::class.java) as Map<String, Any?>
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
        // Keep native temporals as-is (the Neo4j driver supports them directly)
        is ZonedDateTime, is OffsetDateTime, is java.time.LocalDate, is java.time.LocalDateTime, is java.time.LocalTime -> value
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
