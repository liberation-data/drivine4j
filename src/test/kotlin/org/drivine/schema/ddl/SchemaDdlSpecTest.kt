package org.drivine.schema.ddl

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.Neo4jVectorOptions
import org.drivine.schema.VectorIndexSpec
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals

/**
 * The spec's `cypher/schema.yaml`: each step's Neo4j schema command, given to [SchemaDdl.translate], reads as the
 * spec, the drop, the wait or the refusal the step says, or as no schema command. The three libraries read the same file.
 */
class SchemaDdlSpecTest {

    data class Spec(val cases: List<Case> = emptyList())

    data class Case(val name: String, val description: String? = null, val steps: List<Step>)

    data class Step(
        val ddl: String,
        val note: String? = null,
        val create: Map<String, Any?>? = null,
        val drop: Map<String, Any?>? = null,
        val await: Boolean = false,
        val reserved: Map<String, Any?>? = null,
        val cypher: Boolean = false,
        val error: String? = null,
    )

    private val spec: Spec = ObjectMapper(YAMLFactory()).registerKotlinModule()
        .enable(DeserializationFeature.USE_LONG_FOR_INTS)
        .readValue(requireNotNull(javaClass.getResource("/cypher/schema.yaml")) { "No spec file cypher/schema.yaml" })

    @TestFactory
    fun schema(): List<DynamicTest> = spec.cases.map { case ->
        DynamicTest.dynamicTest(case.name) {
            case.steps.forEachIndexed { index, step ->
                val where = "${case.name}, step ${index + 1}: ${step.ddl}"
                assertEquals(expected(step, where), read(SchemaDdl.translate(step.ddl)), where)
            }
        }
    }

    /** What the step says the command reads as, in the form [read] gives. */
    private fun expected(step: Step, where: String): Map<String, Any?> {
        val given = listOfNotNull(
            step.create?.let { mapOf("create" to (createDefaults + it)) },
            step.drop?.let { mapOf("drop" to (dropDefaults + it)) },
            mapOf("await" to true).takeIf { step.await },
            step.reserved?.let { mapOf("reserved" to it) },
            mapOf("cypher" to true).takeIf { step.cypher },
            step.error?.let { mapOf("error" to it) },
        )
        require(given.size == 1) { "$where: a step gives one of create, drop, await, reserved, cypher and error" }
        return given.single()
    }

    private val createDefaults = mapOf("alias" to null, "ifNotExists" to false, "options" to emptyMap<String, Any?>())

    private val dropDefaults = mapOf("ifExists" to false)

    /** [result] as a step writes it. */
    private fun read(result: SchemaDdlResult): Map<String, Any?> = when (result) {
        is SchemaDdlResult.Create -> mapOf(
            "create" to mapOf(
                "kind" to result.spec.kind.name,
                "label" to result.spec.label,
                "properties" to result.spec.properties,
                "alias" to result.alias,
                "ifNotExists" to result.ifNotExists,
                "options" to options(result),
            ),
        )
        is SchemaDdlResult.Drop -> mapOf("drop" to mapOf("kind" to result.kind.name, "name" to result.name, "ifExists" to result.ifExists))
        is SchemaDdlResult.AwaitIndexes -> mapOf("await" to true)
        is SchemaDdlResult.Reserved -> mapOf("reserved" to mapOf("label" to result.spec.label, "properties" to result.spec.properties))
        is SchemaDdlResult.NotDdl -> mapOf("cypher" to true)
        is SchemaDdlResult.Refused -> mapOf("error" to result.message)
    }

    private fun options(result: SchemaDdlResult.Create): Map<String, Any?> = when (val spec = result.spec) {
        is FullTextIndexSpec -> listOfNotNull(spec.analyzer?.let { "analyzer" to it }).toMap()
        is VectorIndexSpec -> listOfNotNull(
            "dimensions" to spec.dimensions.toLong(),
            "similarity" to spec.similarity.name,
            spec.hnswM?.let { "hnswM" to it.toLong() },
            spec.hnswEfConstruction?.let { "hnswEfConstruction" to it.toLong() },
            (spec.optionsFor(Neo4jVectorOptions.ENGINE) as? Neo4jVectorOptions)?.quantizationEnabled?.let { "quantizationEnabled" to it },
        ).toMap()
        else -> emptyMap()
    }
}
