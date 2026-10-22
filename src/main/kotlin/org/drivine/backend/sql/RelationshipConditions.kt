package org.drivine.backend.sql

import org.drivine.model.GraphViewModel
import org.drivine.query.dsl.CollectionSortSpec
import org.drivine.model.RelationshipModel
import org.drivine.query.dsl.WhereCondition

/** How the query DSL's conditions and sorts are read against a view, the same on every SQL store. */
internal object RelationshipConditions {

    /**
     * [conditions] with those on a relationship's alias gathered into one condition on that relationship
     * of [viewModel]: they are met together by one related node, not each by some related node.
     */
    fun grouped(conditions: List<WhereCondition>, viewModel: GraphViewModel?): List<WhereCondition> {
        val relationships = viewModel?.relationships?.map { it.fieldName }?.toSet() ?: return conditions
        val kept = mutableListOf<WhereCondition>()
        val byRelationship = linkedMapOf<String, MutableList<WhereCondition>>()
        conditions.forEach { condition ->
            when (condition) {
                is WhereCondition.PropertyCondition -> {
                    val alias = condition.propertyPath.substringBefore('.')
                    val relationship = if (alias in relationships) alias else alias.substringBefore('_').takeIf { '_' in alias && it in relationships }
                    if (relationship != null) byRelationship.getOrPut(relationship) { mutableListOf() }.add(condition) else kept += condition
                }
                is WhereCondition.LabelCondition ->
                    kept += if (condition.alias in relationships) WhereCondition.RelationshipCondition(condition.alias, listOf(condition)) else condition
                else -> kept += condition
            }
        }
        byRelationship.forEach { (name, conditions) -> kept += WhereCondition.RelationshipCondition(name, conditions) }
        return kept
    }

    /** The sort of [collectionSorts] for [relationship]: by its own name at the top, by its parent's and its own beneath. */
    fun sortOf(collectionSorts: List<CollectionSortSpec>, relationship: RelationshipModel, parentField: String?): CollectionSortSpec? =
        collectionSorts.find {
            if (parentField == null) {
                !it.isNested() && it.relationshipPath == relationship.fieldName
            } else {
                it.isNested() && it.parentRelationship() == parentField && it.nestedRelationship() == relationship.fieldName
            }
        }
}
