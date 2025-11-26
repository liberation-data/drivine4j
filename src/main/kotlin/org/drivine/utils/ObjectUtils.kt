package org.drivine.utils

import java.beans.Introspector
import java.lang.reflect.Modifier
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.Date
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

object ObjectUtils {

    @JvmStatic
    @JvmOverloads
    fun primitiveProps(obj: Any, includeNulls: Boolean = true): Map<String, Any?> {
        // If it's already a Map, process it recursively
        if (obj is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            return primitiveProps(obj as Map<String, Any?>, includeNulls)
        }

        // Check if this is a pure Java class (no Kotlin metadata)
        val isJavaClass = obj.javaClass.getAnnotation(Metadata::class.java) == null

        // For Java classes, use Java reflection directly
        if (isJavaClass) {
            return toJavaMap(obj, includeNulls)
        }

        // Try Kotlin reflection first (if kotlin-reflect is on the classpath)
        return try {
            toKotlinMap(obj, includeNulls)
        } catch (_: Throwable) {
            // Fallbacks for plain Java objects
            toJavaMap(obj, includeNulls)
        }
    }

    /**
     * Overload for processing a map recursively.
     * Converts values to Neo4j-compatible types (e.g., Instant -> ZonedDateTime).
     */
    @JvmStatic
    @JvmOverloads
    fun primitiveProps(map: Map<String, Any?>, includeNulls: Boolean = true): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        for ((key, value) in map) {
            val converted = convertValue(value)
            if (converted != null) {
                result[key] = converted
            } else if (value == null && includeNulls) {
                result[key] = null
            }
        }
        return result
    }

    // ---------- Kotlin reflection path ----------
    private fun toKotlinMap(obj: Any, includeNulls: Boolean): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        val kClass: KClass<*> = obj::class

        for (prop in kClass.memberProperties) {
            try {
                prop.isAccessible = true

                // Variance-safe access:
                @Suppress("UNCHECKED_CAST")
                val kprop = prop as KProperty1<Any?, Any?>

                val value = kprop.get(obj)
                val converted = convertValue(value)

                // Only include if conversion succeeded, or if value was originally null and includeNulls=true
                if (converted != null) {
                    map[prop.name] = converted
                } else if (value == null && includeNulls) {
                    map[prop.name] = null
                }
            } catch (_: Exception) {
                // Ignore inaccessible property or getter failure
            }
        }
        return map
    }

    // ---------- Java reflection path ----------
    private fun toJavaMap(obj: Any, includeNulls: Boolean): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        val clazz = obj.javaClass

        // 1) Prefer bean getters if available (covers private fields with public getters)
        try {
            val info = Introspector.getBeanInfo(clazz, Object::class.java)
            for (pd in info.propertyDescriptors) {
                val read = pd.readMethod ?: continue
                if (read.parameterCount != 0) continue
                read.isAccessible = true
                val value = read.invoke(obj)
                val converted = convertValue(value)

                // Only include if conversion succeeded, or if value was originally null and includeNulls=true
                if (converted != null) {
                    map[pd.name] = converted
                } else if (value == null && includeNulls) {
                    map[pd.name] = null
                }
            }
            if (map.isNotEmpty()) return map
        } catch (_: Exception) {
            // Fall through to fields
        }

        // 2) Otherwise, read instance fields directly (including inherited fields)
        var currentClass: Class<*>? = clazz
        while (currentClass != null && currentClass != Object::class.java) {
            for (field in currentClass.declaredFields) {
                // Skip if already processed (subclass may have overridden field)
                if (field.name in map) continue

                if (Modifier.isStatic(field.modifiers)) continue
                if (Modifier.isTransient(field.modifiers)) continue
                if (field.isSynthetic) continue

                field.isAccessible = true
                try {
                    val value = field.get(obj)
                    val converted = convertValue(value)

                    // Only include if conversion succeeded, or if value was originally null and includeNulls=true
                    if (converted != null) {
                        map[field.name] = converted
                    } else if (value == null && includeNulls) {
                        map[field.name] = null
                    }
                } catch (_: Exception) {
                    // Ignore
                }
            }
            currentClass = currentClass.superclass
        }
        return map
    }

    // ---------- Helpers ----------

    /**
     * Converts a value to a Neo4j-compatible primitive type.
     * - Enum -> String (using enum.name)
     * - UUID -> String
     * - Instant -> ZonedDateTime (at UTC) - Neo4j driver doesn't support Instant directly
     * - LocalDate, LocalDateTime, ZonedDateTime -> preserved as-is (native Neo4j temporal types)
     * - Date -> Instant -> ZonedDateTime (at UTC)
     * - Maps -> converted recursively
     * - Collections/Arrays -> converted recursively
     * - null -> null
     * - Primitives -> as-is
     * - Other types -> null (not included)
     */
    private fun convertValue(v: Any?): Any? {
        return when (v) {
            null -> null
            is String, is Number, is Boolean -> v
            is Enum<*> -> v.name  // Convert enum to its string name for Neo4j
            is UUID -> v.toString()

            // Temporal types - Neo4j driver supports LocalDate, LocalDateTime, ZonedDateTime, etc.
            // but NOT Instant, so we convert Instant to ZonedDateTime at UTC
            is Instant -> v.atZone(java.time.ZoneId.of("UTC"))
            is ZonedDateTime -> v  // Already supported by Neo4j driver
            is LocalDateTime -> v  // Already supported by Neo4j driver
            is LocalDate -> v      // Already supported by Neo4j driver
            is Date -> v.toInstant().atZone(java.time.ZoneId.of("UTC"))

            // Nested maps - process recursively
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val stringMap = v as? Map<String, Any?> ?: return null
                primitiveProps(stringMap, includeNulls = true)
            }

            // Object arrays
            is Array<*> -> {
                val converted = v.mapNotNull { convertValue(it) }
                if (converted.size == v.size) converted else null
            }

            // Primitive arrays (already compatible)
            is IntArray, is LongArray, is ShortArray, is ByteArray,
            is DoubleArray, is FloatArray, is CharArray, is BooleanArray -> v

            // Collections
            is Collection<*> -> {
                val converted = v.mapNotNull { convertValue(it) }
                if (converted.size == v.size) converted else null
            }

            else -> null
        }
    }

    private fun isPrimitiveLike(v: Any?): Boolean {
        return when (v) {
            null -> true
            is String, is Number, is Boolean, is Enum<*> -> true

            // Object arrays
            is Array<*> -> v.all { isPrimitiveLike(it) }

            // Primitive arrays
            is IntArray -> true
            is LongArray -> true
            is ShortArray -> true
            is ByteArray -> true
            is DoubleArray -> true
            is FloatArray -> true
            is CharArray -> true
            is BooleanArray -> true

            // Collections of primitive-like
            is Collection<*> -> v.all { isPrimitiveLike(it) }

            else -> false
        }
    }
}
