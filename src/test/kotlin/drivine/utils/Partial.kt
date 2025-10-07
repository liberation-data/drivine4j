package drivine.utils

import kotlin.reflect.*
import kotlin.reflect.full.*
import kotlin.reflect.jvm.isAccessible

/**
 * Tri-state value for a property in a Partial:
 *  - Absent: not provided (leave target value as-is)
 *  - Present(value): set to value (value may be null)
 */
sealed class Field<out T> {
    data object Absent : Field<Nothing>()
    data class Present<out T>(val value: T) : Field<T>()
}

/**
 * A “Partial<T>”: a sparse set of property updates for T.
 * Uses KProperty references for type-safety.
 */
class Partial<T : Any> internal constructor(
    private val entries: Map<KProperty1<T, *>, Field<Any?>>
) {
    fun isEmpty(): Boolean = entries.values.all { it is Field.Absent }

    /** True if this partial explicitly touches the given property (even if value = null). */
    fun <V> touches(prop: KProperty1<T, V>): Boolean = entries.containsKey(prop as KProperty1<T, *>)

    /** Get the field state for a property (Absent if not set). */
    fun <V> field(prop: KProperty1<T, V>): Field<V?> =
        (entries[prop as KProperty1<T, *>] as Field<V?>?) ?: Field.Absent

    /** Merge two partials; `other` wins on conflicts. */
    operator fun plus(other: Partial<T>): Partial<T> =
        Partial(entries + other.entries)

    /** Convert to a simple name→Field map (handy for dynamic callers). */
    fun toNamedFields(): Map<String, Field<Any?>> =
        entries.mapKeys { it.key.name }

    /**
     * Apply this partial to a target:
     *  - If T is a data class: use `copy` while preserving untouched params.
     *  - Else: mutate via setters when available (KMutableProperty1).
     */
    fun applyTo(target: T): T {
        val kClass = target::class
        return if (kClass.isData) applyToDataClass(target, kClass) else applyToMutableClass(target, kClass)
    }

    private fun applyToDataClass(target: T, kClass: KClass<out T>): T {
        // Find primary constructor parameters by name to line up with copy()
        val copyFun = kClass.memberFunctions.firstOrNull { it.name == "copy" }
            ?: error("No copy() found on data class ${kClass.simpleName}")

        // Build map for copy()'s parameters (first parameter is the instance receiver)
        val byName: Map<String, KProperty1<T, *>> =
            kClass.memberProperties.associateBy { it.name } as Map<String, KProperty1<T, *>>

        val callArgs = mutableMapOf<KParameter, Any?>()
        copyFun.parameters.forEach { p ->
            if (p.kind == KParameter.Kind.INSTANCE) {
                callArgs[p] = target
                return@forEach
            }
            val name = p.name ?: return@forEach
            val prop = byName[name] ?: return@forEach

            when (val field = entries[prop]) {
                is Field.Present<*> -> callArgs[p] = field.value
                null, Field.Absent -> {
                    // read original value from target for untouched params
                    prop.isAccessible = true
                    callArgs[p] = prop.get(target)
                }
            }
        }
        return copyFun.callBy(callArgs) as T
    }

    private fun applyToMutableClass(target: T, kClass: KClass<out T>): T {
        // Update mutable properties in-place; ignore non-mutable props
        @Suppress("UNCHECKED_CAST")
        val mutableProps = kClass.memberProperties
            .filterIsInstance<KMutableProperty1<T, Any?>>()
            .associateBy { it.name }

        entries.forEach { (prop, field) ->
            val name = prop.name
            val mutable = mutableProps[name] ?: return@forEach
            if (field is Field.Present) {
                mutable.isAccessible = true
                mutable.set(target, field.value)
            }
        }
        return target
    }

    companion object {
        /** Build a Partial from a name→value map (value null is Present(null)). */
        inline fun <reified T : Any> fromMap(map: Map<String, Any?>): Partial<T> =
            partial<T> {
                val props = T::class.memberProperties.associateBy { it.name }
                map.forEach { (name, value) ->
                    val p = props[name] ?: return@forEach
                    @Suppress("UNCHECKED_CAST")
                    set(p as KProperty1<T, Any?>, value)
                }
            }
    }
}

/** Builder DSL for Partial<T>. */
class PartialBuilder<T : Any>() {
    private val mut = LinkedHashMap<KProperty1<T, *>, Field<Any?>>()

    /** Set property to a value (including null). */
    fun <V> set(prop: KProperty1<T, V>, value: V) {
        mut[prop as KProperty1<T, *>] = Field.Present(value)
    }

    /**
     * Mark property as absent (i.e., remove any explicit setting in this Partial),
     * not to be confused with `set(prop, null)` which is Present(null).
     */
    fun <V> unset(prop: KProperty1<T, V>) {
        mut[prop as KProperty1<T, *>] = Field.Absent
    }

    fun build(): Partial<T> = Partial(mut.toMap())
}

/** Entry point for the DSL: */
inline fun <reified T : Any> partial(build: PartialBuilder<T>.() -> Unit): Partial<T> =
    PartialBuilder<T>().apply(build).build()

/** Convenience: merge many partials. */
inline fun <reified T : Any> mergePartials(vararg parts: Partial<T>): Partial<T> =
    parts.fold(partial<T> { }) { acc, p -> acc + p }

/** Apply syntactic sugar. */
fun <T : Any> T.patchedWith(p: Partial<T>): T = p.applyTo(this)

/** Names → values for the Present fields in this Partial. */
fun <T : Any> Partial<T>.toMap(): Map<String, Any?> =
    this.toNamedFields()
        .mapNotNull { (name, field) ->
            when (field) {
                is Field.Present -> name to field.value
                Field.Absent -> null
            }
        }
        .toMap()

/**
 * Diff against a specific instance: name → (oldValue, newValue)
 * Only includes properties that are Present in this Partial.
 */
fun <T : Any> Partial<T>.diffAgainst(target: T): Map<String, Pair<Any?, Any?>> {
    val kClass = target::class
    val byName = kClass.memberProperties.associateBy { it.name }

    return this.toNamedFields().mapNotNull { (name, field) ->
        if (field is Field.Present) {
            val prop = byName[name] ?: return@mapNotNull null
            prop.isAccessible = true
            val oldVal = prop.getter.call(target)
            name to (oldVal to field.value)
        } else null
    }.toMap()
}

/* ---------- Notes ----------
 * - Absent vs Present(null): `unset(prop)` means “don’t touch”; `set(prop, null)` means “set to null”.
 * - Data classes: preserves all untouched primary-constructor params via copy().
 * - Non-data classes: updates only mutable properties (KMutableProperty1) in-place.
 * - Java beans interop: if you expose Kotlin mutable properties (var) that map to setters, it works fine.
 * - Performance: reflection is used; for hot paths, you can memoize copy parameter resolution.
 */
