package org.drivine.sample

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * The model file the build writes, `.drivine/model.json`, read against what the core library reads
 * from the same classes when the application runs. The file comes from the code generator and the
 * models from reflection, so this is what keeps the two telling Drivine Studio the same thing.
 */
class ModelFileTest {

    private val model: JsonNode = ObjectMapper().readTree(File(".drivine/model.json"))

    @Test
    fun `each fragment is stored as the core library stores it`() {
        model["fragments"].forEach { fragment ->
            val runtime = FragmentModel.from(Class.forName(fragment["id"].asText()))
            val id = fragment["id"].asText()
            assertEquals(runtime.labels, fragment["labels"].map { it.asText() }, id)
            assertEquals(runtime.nodeIdField, fragment["nodeId"]?.asText(), id)
            assertEquals(runtime.stampField != null, fragment["stamp"]?.asBoolean() ?: false, id)
            // The stamp's field is bookkeeping, which the file does not list as a property.
            assertEquals(
                runtime.fields.filter { !it.stamp }.associate { it.name to it.propertyName },
                fragment["properties"].associate { it["name"].asText() to it["stored"].asText() },
                id,
            )
            assertEquals(
                runtime.propertyBags.associate { it.fieldName to it.storedPrefix },
                fragment["bags"]?.associate { it["field"].asText() to it["prefix"].asText() }.orEmpty(),
                id,
            )
            assertEquals(runtime.vectorFieldNames, fragment["properties"].filter { it.has("vector") }.map { it["name"].asText() }.toSet(), id)
        }
    }

    @Test
    fun `each view loads as the core library loads it`() {
        model["views"].forEach { view ->
            val id = view["id"].asText()
            val runtime = GraphViewModel.from(Class.forName(id))
            assertEquals(runtime.rootFragment.fieldName, view["root"]["field"].asText(), id)
            assertEquals(runtime.rootFragment.fragmentType.name, view["root"]["fragment"].asText(), id)
            assertEquals(
                runtime.relationships.sortedBy { it.fieldName }.map {
                    listOf(
                        it.fieldName, if (it.isPath) null else it.type, if (it.isPath) null else it.direction.name,
                        it.elementType.name, it.isCollection, !it.isCollection && !it.isNullable, it.maxDepth, it.readOnly,
                        it.sortBy, it.sortBy?.let { _ -> it.sortAscending },
                    )
                },
                view["relationships"].map {
                    listOf(
                        it["field"].asText(), it["type"]?.asText(), it["direction"]?.asText(),
                        it["target"].asText(), it["many"].asBoolean(), it["required"].asBoolean(),
                        it["maxDepth"]?.asInt() ?: 1, it["readOnly"]?.asBoolean() ?: false,
                        it["sortedBy"]?.get("property")?.asText(), it["sortedBy"]?.get("ascending")?.asBoolean(),
                    )
                },
                id,
            )
        }
    }

    @Test
    fun `each relationship fragment points where the core library points it`() {
        val targets: Map<String, List<String?>> = model["views"]
            .flatMap { view -> GraphViewModel.from(Class.forName(view["id"].asText())).relationships }
            .filter { it.isRelationshipFragment }
            .associate { it.elementType.name to listOf(it.targetFieldName, it.targetNodeType?.name) }
        for (fragment: JsonNode in model["relationshipFragments"]) {
            val id = fragment["id"].asText()
            val target = fragment["target"]
            assertEquals(targets[id], listOf(target["field"].asText(), target["fragment"].asText()), id)
        }
    }
}
