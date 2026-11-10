package org.drivine.schema

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory

/**
 * The Cypher spec's `store.yaml`, in `src/test/resources/cypher`: the store layout's DDL for a set of schema specs, and a
 * read for each index that must be answered by it. See [StoreDdlGoldenTest] for where the copy comes from.
 */
object StoreYaml {

    /** The file, as a tree. */
    val root: JsonNode by lazy {
        ObjectMapper(YAMLFactory()).readTree(requireNotNull(StoreYaml::class.java.getResource("/cypher/store.yaml")) { "No spec file cypher/store.yaml" })
    }

    /** The cases, each an index or a constraint with what each store makes of it. */
    val cases: List<JsonNode> get() = root["cases"].toList()

    /** A read of an index's [case]: its Cypher, its parameters, and on Oracle the labelled indexes it is compiled knowing. */
    class Read(val case: String, val spec: SchemaItemSpec, node: JsonNode) {
        val cypher: String = node["cypher"].textValue()
        val parameters: Map<String, Any?> = node["parameters"]?.properties()?.associate { (key, value) -> key to value.textValue() }.orEmpty()
        val indexedProperties: Set<Pair<String, String>> = node["indexedProperties"].map { it[0].textValue() to it[1].textValue() }.toSet()

        /** The expressions an index answering the read on [store] is made over, as its DDL writes them. */
        val holds: Map<String, List<String>> = node["holds"].properties().associate { (store, expressions) -> store to texts(expressions) }

        override fun toString() = case
    }

    /** Each case that has a read, with its read. */
    val reads: List<Read> get() = cases.mapNotNull { case -> case["read"]?.let { Read(case["name"].textValue(), spec(case["spec"]), it) } }

    /** The spec `store.yaml` writes, as `schema.yaml` writes one, with `ignoreCase` and each engine's vector options beside. */
    fun spec(node: JsonNode): SchemaItemSpec {
        val label = node["label"].textValue()
        val properties = texts(node["properties"])
        val name = node["name"]?.textValue()
        val ignoreCase = node["ignoreCase"]?.booleanValue() ?: false
        val options = node["options"]
        fun option(key: String) = options?.get(key)?.takeUnless { it.isNull }
        return when (SchemaItemKind.valueOf(node["kind"].textValue())) {
            SchemaItemKind.RANGE_INDEX -> RangeIndexSpec(label, properties, name, ignoreCase)
            SchemaItemKind.UNIQUENESS_CONSTRAINT -> UniquenessConstraintSpec(label, properties, name, ignoreCase = ignoreCase)
            SchemaItemKind.FULLTEXT_INDEX -> FullTextIndexSpec(label, properties, name, option("analyzer")?.textValue())
            SchemaItemKind.TEXT_INDEX -> TextIndexSpec(label, properties.single(), name, ignoreCase)
            SchemaItemKind.VECTOR_INDEX -> VectorIndexSpec(
                label, properties.single(), option("dimensions")!!.intValue(),
                SimilarityFunction.valueOf(option("similarity")!!.textValue()), name,
                option("hnswM")?.intValue(), option("hnswEfConstruction")?.intValue(),
                listOfNotNull(
                    option("quantizationEnabled")?.let { Neo4jVectorOptions(quantizationEnabled = it.booleanValue()) },
                    option("efRuntime")?.let { FalkorDbVectorOptions(efRuntime = it.intValue()) },
                ),
            )
        }
    }

    fun texts(node: JsonNode): List<String> = node.map { it.textValue() }
}
