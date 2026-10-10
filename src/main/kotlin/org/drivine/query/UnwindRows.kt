package org.drivine.query

import org.drivine.manager.NullPolicy
import org.drivine.model.FragmentModel
import org.drivine.model.Stamps
import org.drivine.query.grammar.CypherGrammar

/**
 * Whether nodes of this fragment can be saved as the rows of one `UNWIND`, by `SET n += row.props`.
 * A `@PropertyBag` cannot: clearing its stale keys needs each object's own keys. A `@NodeLabels` field
 * cannot: labels are written into the statement. A `@VectorIndex` field cannot on an engine that wraps
 * a vector as it writes it (FalkorDB).
 */
internal fun FragmentModel.savedByUnwind(grammar: CypherGrammar?): Boolean =
    nodeIdField != null && propertyBags.isEmpty() && nodeLabels == null &&
        !(vectorFieldNames.isNotEmpty() && grammar?.wrapsVectorLiteral == true)

/**
 * The `props` of an `UNWIND` row for a fragment whose fields are [values], keyed by stored property
 * name. The id and the stamp are left out: the id is the row's key, and the stamp is never written from
 * the object. Under [NullPolicy.IGNORE] a null is left out, so `+=` does not touch it; under
 * [NullPolicy.CLEAR] it is kept, so `+=` clears the property.
 */
internal fun FragmentModel.unwindProps(values: Map<String, Any?>, nullPolicy: NullPolicy): Map<String, Any?> {
    val propertyNameByField = fields.associate { it.name to it.propertyName }
    return values
        .filterKeys { it != nodeIdField && it != stampField }
        .filter { (_, value) -> value != null || nullPolicy == NullPolicy.CLEAR }
        .mapKeys { (field, _) -> propertyNameByField[field] ?: field }
}

/**
 * Whether `row.props` changes the node `n`: a property that differs, one cleared that held a value, or
 * a node with no stamp yet. A comparison that cannot tell counts as a change.
 */
internal val ROW_CHANGES_NODE = """
    (n.${Stamps.QUOTED} IS NULL OR any(k IN keys(row.props) WHERE
        CASE WHEN row.props[k] IS NULL THEN n[k] IS NOT NULL ELSE NOT coalesce(n[k] = row.props[k], false) END))
""".trimIndent()
