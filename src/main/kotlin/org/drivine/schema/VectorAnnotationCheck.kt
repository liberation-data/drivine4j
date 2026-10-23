package org.drivine.schema

import org.drivine.DrivineException
import org.drivine.model.FragmentModel
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Refuses a vector index declared in a [SchemaCatalog] over a fragment field that has no
 * [org.drivine.annotation.VectorIndex].
 *
 * Drivine knows a field is an embedding from the annotation alone ([FragmentModel.vectorFieldNames]):
 * without it the field is not written as the engine's vector type on FalkorDB, and `loadNearest`
 * finds no index to search. Neither shows where the mistake was made, so it is refused there.
 *
 * Either side may be seen first — a catalog can be applied before a fragment class is used, or after —
 * so both are kept for the life of the process: the vector specs [SchemaManager] has applied, and the
 * fragment classes whose [FragmentModel] has been built. Each new arrival is checked against the other.
 * A spec for a label no fragment maps has nothing to check, and a spec beside the annotation is the
 * documented way to tune an index.
 *
 * Classes are held weakly, so a classloader dropped by a restart (Spring Boot devtools) is not kept alive.
 */
internal object VectorAnnotationCheck {

    private val specs = mutableSetOf<VectorIndexSpec>()

    /** Whether a class has passed: the lock-free check every [FragmentModel.from] makes. */
    private val passed = object : ClassValue<AtomicBoolean>() {
        override fun computeValue(type: Class<*>) = AtomicBoolean(false)
    }

    /** The classes that have passed, for [register] to check again; guarded by the object's lock. */
    private val admitted = WeakHashMap<Class<*>, Unit>()

    /** Records the vector specs of [catalogs] and checks them against every fragment admitted so far. */
    fun register(catalogs: List<SchemaCatalog>) {
        val declared = catalogs.flatMap { it.indexes }.filterIsInstance<VectorIndexSpec>()
        if (declared.isEmpty()) return
        synchronized(this) {
            specs += declared
            // Rebuilt rather than kept, so nothing here holds a class strongly. An admitted class returns from admit at once.
            refuse(admitted.keys.toList().flatMap { uncovered(FragmentModel.from(it), declared) })
        }
    }

    /** Checks a newly built [model] against the vector specs registered so far, once per class. */
    fun admit(model: FragmentModel) {
        val flag = passed.get(model.clazz)
        if (flag.get()) return
        synchronized(this) {
            // Not admitted when refused, so every later build of the class is refused too.
            refuse(uncovered(model, specs))
            admitted[model.clazz] = Unit
            flag.set(true)
        }
    }

    private fun uncovered(model: FragmentModel, specs: Collection<VectorIndexSpec>): List<String> =
        specs.filter { it.label in model.labels }.flatMap { spec ->
            model.fields
                .filter { it.propertyName == spec.property && !it.vectorIndexed }
                .map { field ->
                    "`${model.clazz.simpleName}.${field.name}` is covered by the vector index on " +
                        "`${spec.label}.${spec.property}` but is not annotated `@VectorIndex`. Add the annotation: " +
                        "without it the field is not written as a vector on FalkorDB, and `loadNearest` cannot find its index."
                }
        }.distinct()

    private fun refuse(problems: List<String>) {
        if (problems.isNotEmpty()) throw DrivineException(problems.joinToString("\n"))
    }
}
