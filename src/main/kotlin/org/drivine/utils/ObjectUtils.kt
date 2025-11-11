package org.drivine.utils

import java.beans.Introspector
import java.lang.reflect.Modifier
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

object ObjectUtils {

    @JvmStatic
    @JvmOverloads
    fun primitiveProps(obj: Any, includeNulls: Boolean = true): Map<String, Any?> {
        // Try Kotlin reflection first (if kotlin-reflect is on the classpath)
        return try {
            toKotlinMap(obj, includeNulls)
        } catch (_: Throwable) {
            // Fallbacks for plain Java objects
            toJavaMap(obj, includeNulls)
        }
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

        // 2) Otherwise, read instance fields directly
        for (field in clazz.declaredFields) {
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
        return map
    }

    // ---------- Helpers ----------

    /**
     * Converts a value to a Neo4j-compatible primitive type.
     * - UUID -> String
     * - Collections/Arrays -> converted recursively
     * - null -> null
     * - Primitives -> as-is
     * - Other types -> null (not included)
     */
    private fun convertValue(v: Any?): Any? {
        return when (v) {
            null -> null
            is String, is Number, is Boolean, is Enum<*> -> v
            is UUID -> v.toString()

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
