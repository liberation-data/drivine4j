package org.drivine.query

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.GraphView
import org.drivine.manager.CascadeType
import org.drivine.manager.NullPolicy
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.query.grammar.CypherGrammar
import org.drivine.session.SessionManager

/**
 * Base interface for building MERGE statements for graph objects (Fragments and Views).
 */
interface GraphObjectMergeBuilder {
    /**
     * Builds a list of MERGE statements to save a graph object.
     * Returns statements in execution order.
     *
     * @param obj The object to save
     * @param cascade The cascade policy for deleted relationships
     * @param nullPolicy How null field values are treated (see [NullPolicy]); defaults to
     *   [NullPolicy.IGNORE] (merge-patch). Applies to the (root) fragment being saved.
     * @return List of MergeStatements to execute in order
     */
    fun <T : Any> buildMergeStatements(
        obj: T,
        cascade: CascadeType = CascadeType.NONE,
        nullPolicy: NullPolicy = NullPolicy.IGNORE,
    ): List<MergeStatement>

    companion object {
        /**
         * Creates the appropriate merge builder for a graph object class.
         * Detects whether it's a GraphFragment or GraphView and returns the correct builder.
         */
        fun forClass(
            graphClass: Class<*>,
            objectMapper: ObjectMapper,
            sessionManager: SessionManager,
            grammar: CypherGrammar? = null,
            storedKeys: StoredPropertyKeys? = null,
            stamping: Stamping? = null,
            rootWriteFields: Set<String>? = null,
        ): GraphObjectMergeBuilder {
            return if (graphClass.isAnnotationPresent(GraphView::class.java)) {
                val viewModel = GraphViewModel.from(graphClass)
                GraphViewMergeBuilder(viewModel, objectMapper, sessionManager, grammar, storedKeys, stamping, rootWriteFields)
            } else if (graphClass.isAnnotationPresent(NodeFragment::class.java)) {
                val fragmentModel = FragmentModel.from(graphClass)
                FragmentMergeBuilderAdapter(fragmentModel, objectMapper, sessionManager, grammar, storedKeys, stamping, rootWriteFields)
            } else {
                throw IllegalArgumentException("Class ${graphClass.name} must be annotated with @GraphView or @GraphFragment")
            }
        }

        /**
         * Creates the appropriate merge builder for a graph object class using KClass.
         */
        fun forClass(
            graphClass: kotlin.reflect.KClass<*>,
            objectMapper: ObjectMapper,
            sessionManager: SessionManager,
            grammar: CypherGrammar? = null,
            storedKeys: StoredPropertyKeys? = null,
            stamping: Stamping? = null,
        ): GraphObjectMergeBuilder {
            return forClass(graphClass.java, objectMapper, sessionManager, grammar, storedKeys, stamping)
        }
    }
}

/**
 * Adapter to make FragmentMergeBuilder conform to GraphObjectMergeBuilder interface.
 * Wraps the single-statement fragment builder into a list-based interface.
 */
class FragmentMergeBuilderAdapter(
    private val fragmentModel: FragmentModel,
    private val objectMapper: ObjectMapper,
    private val sessionManager: SessionManager,
    private val grammar: CypherGrammar? = null,
    private val storedKeys: StoredPropertyKeys? = null,
    private val stamping: Stamping? = null,
    private val writeFields: Set<String>? = null,
) : GraphObjectMergeBuilder {

    override fun <T : Any> buildMergeStatements(obj: T, cascade: CascadeType, nullPolicy: NullPolicy): List<MergeStatement> {
        val fragmentBuilder = FragmentMergeBuilder(fragmentModel, objectMapper, grammar, storedKeys, stamping)

        // One lookup: the previous digest gives both the dirty fields and the stale @PropertyBag keys.
        // Untracked (never loaded, or evicted) means a full save.
        val idValue = sessionManager.extractIdValue(obj, fragmentModel)?.toString()
        val previous = idValue?.let { sessionManager.snapshotOf(obj.javaClass, it) }
        val dirtyFields = previous?.let { sessionManager.computeDirtyFields(obj, it) }

        // Note: Fragments don't have relationships, so cascade is ignored
        return listOf(fragmentBuilder.buildMergeStatement(obj, dirtyFields, previous, nullPolicy, writeFields))
    }
}
