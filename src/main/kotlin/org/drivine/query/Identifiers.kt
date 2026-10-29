package org.drivine.query

import org.drivine.model.FragmentField
import org.drivine.model.FragmentModel
import org.drivine.schema.SchemaGrammar

/**
 * [name] as a label, relationship type or property name written into a statement: always quoted, so
 * it is one identifier whatever characters it holds. Names that cannot be bound as parameters — a
 * label or type known only at runtime — reach the statement through here and nowhere else.
 */
internal fun quotedIdentifier(name: String): String = SchemaGrammar.quoted(name)

/**
 * The check a search makes of each node its index yields, that it has every one of [labels], or null
 * when [labels] is the [indexedLabel] alone, which every node the index yields has. An index is made
 * and searched on a fragment's first label, so a node with that label alone is in it, and is no node
 * of a fragment with more; a partition's index is on the partition's label, so a node in it may have
 * none of the fragment's. A load, which matches every label, returns neither.
 */
internal fun indexedNodeLabelCheck(alias: String, labels: List<String>, indexedLabel: String): String? =
    if (labels.isEmpty() || labels == listOf(indexedLabel)) null
    else alias + labels.joinToString("") { ":${quotedIdentifier(it)}" }

/**
 * The property this field is stored under, as a load spells it after an alias: bare when it is a
 * plain identifier, backtick-quoted otherwise, by the one rule schema DDL and the query DSL spell
 * names by. A `@GraphProperty` may name anything, a dotted name included. The stamp's property is
 * held quoted already.
 */
internal val FragmentField.storedReference: String
    get() = if (stamp) propertyName else SchemaGrammar.identifier(propertyName)

/** The stored property of the `@NodeId` field, spelt as [storedReference] spells one; null if there is none. */
internal val FragmentModel.nodeIdReference: String?
    get() = nodeIdProperty?.let { SchemaGrammar.identifier(it) }
