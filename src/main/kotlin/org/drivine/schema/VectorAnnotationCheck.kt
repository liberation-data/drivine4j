package org.drivine.schema

import org.drivine.DrivineException
import org.drivine.model.FragmentModel
import java.util.concurrent.ConcurrentHashMap

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
 */
internal object VectorAnnotationCheck {

    private val specs = mutableSetOf<VectorIndexSpec>()
    private val admitted = ConcurrentHashMap<Class<*>, FragmentModel>()

    /** Records the vector specs of [catalogs] and checks them against every fragment admitted so far. */
    fun register(catalogs: List<SchemaCatalog>) {
        val declared = catalogs.flatMap { it.indexes }.filterIsInstance<VectorIndexSpec>()
        if (declared.isEmpty()) return
        synchronized(this) {
            specs += declared
            refuse(admitted.values.flatMap { uncovered(it, declared) })
        }
    }

    /** Checks a newly built [model] against the vector specs registered so far, once per class. */
    fun admit(model: FragmentModel) {
        if (admitted.containsKey(model.clazz)) return
        synchronized(this) {
            // Not admitted when refused, so every later build of the class is refused too.
            refuse(uncovered(model, specs))
            admitted[model.clazz] = model
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
