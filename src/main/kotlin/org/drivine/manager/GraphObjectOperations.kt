package org.drivine.manager

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.mapper.SubtypeRegistry
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.mapper.TransformPostProcessor
import org.drivine.query.FullTextSearchPlanner
import org.drivine.query.GraphObjectMergeBuilder
import org.drivine.query.GraphObjectQueryBuilder
import org.drivine.query.GraphViewQueryBuilder
import org.drivine.query.QuerySpecification
import org.drivine.query.ScoredSearchPlan
import org.drivine.query.Stamping
import org.drivine.query.StoredPropertyKeys
import org.drivine.query.VectorSearchPlanner
import org.drivine.query.dsl.CypherGenerator
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.OrderClauseResult
import org.drivine.query.dsl.KeysetPlanner
import org.drivine.query.dsl.IndexAdvicePolicy
import org.drivine.query.dsl.OrderSpec
import org.drivine.query.dsl.QueryIndexAdvisor
import org.drivine.query.dsl.WhereCondition
import org.drivine.query.dsl.ComparisonOperator
import org.drivine.session.SessionManager
import org.drivine.store.StoreIdentity
import org.slf4j.LoggerFactory

/**
 * Loading, querying and deleting graph objects: what [GraphObjectManager] and
 * [StatelessGraphObjectManager] have in common. They differ in how they save.
 *
 * The reified Kotlin forms and the generated query DSL are extensions of this interface, so they
 * work on either manager.
 */
interface GraphObjectOperations {

    /** The name of the database this manager is connected to. */
    val database: String

    /** Stable identity of the store this manager's objects live in. See [StoreIdentity]. */
    val storeIdentity: StoreIdentity

    /** Relationships whose type is known only at runtime, between stored nodes. See [EdgeOperations]. */
    val edges: EdgeOperations

    /** What to do when an ordered or paginated query has no range index to seek into. */
    var indexAdvice: IndexAdvicePolicy

    fun <T : Any> loadAll(graphClass: Class<T>): List<T>

    fun <T : Any> loadAll(graphClass: Class<T>, whereClause: String): List<T>

    fun <T : Any> count(graphClass: Class<T>): Long

    fun <T : Any> count(graphClass: Class<T>, whereClause: String): Long

    fun <T : Any, Q : Any> count(
        graphClass: Class<T>,
        queryObject: Q,
        spec: GraphQuerySpec<Q>.() -> Unit,
    ): Long

    fun <T : Any, Q : Any> loadAll(
        graphClass: Class<T>,
        queryObject: Q,
        spec: GraphQuerySpec<Q>.() -> Unit
    ): List<T>

    fun <T : Any> load(id: String, graphClass: Class<T>): T?

    fun <T : Any> loadNearest(
        graphClass: Class<T>,
        vector: List<Float>,
        topK: Int,
        threshold: Double? = null,
        searchK: Int? = null,
        partitionLabel: String? = null,
    ): List<Scored<T>>

    fun <T : Any> loadNearest(
        graphClass: Class<T>,
        property: String?,
        vector: List<Float>,
        topK: Int,
        threshold: Double? = null,
        searchK: Int? = null,
        partitionLabel: String? = null,
    ): List<Scored<T>>

    fun <T : Any, Q : Any> loadNearest(
        graphClass: Class<T>,
        queryObject: Q,
        vector: List<Float>,
        topK: Int,
        threshold: Double? = null,
        searchK: Int? = null,
        partitionLabel: String? = null,
        spec: GraphQuerySpec<Q>.() -> Unit,
    ): List<Scored<T>>

    fun <T : Any> loadMatching(
        graphClass: Class<T>,
        query: String,
        topK: Int,
        threshold: Double = 0.0,
    ): List<Scored<T>>

    fun <T : Any> loadMatching(
        graphClass: Class<T>,
        property: String?,
        query: String,
        topK: Int,
        threshold: Double = 0.0,
    ): List<Scored<T>>

    fun <T : Any, Q : Any> loadMatching(
        graphClass: Class<T>,
        queryObject: Q,
        query: String,
        topK: Int,
        threshold: Double = 0.0,
        spec: GraphQuerySpec<Q>.() -> Unit,
    ): List<Scored<T>>

    fun <T : Any> delete(id: String, graphClass: Class<T>): Int

    fun <T : Any> delete(id: String, graphClass: Class<T>, cascade: CascadeType): Int

    fun <T : Any> delete(id: String, graphClass: Class<T>, whereClause: String?): Int

    fun <T : Any> delete(id: String, graphClass: Class<T>, whereClause: String?, cascade: CascadeType): Int

    fun <T : Any> deleteAll(graphClass: Class<T>): Int

    fun <T : Any> deleteAll(graphClass: Class<T>, whereClause: String?): Int

    fun <T : Any, Q : Any> deleteAll(
        graphClass: Class<T>,
        queryObject: Q,
        spec: GraphQuerySpec<Q>.() -> Unit
    ): Int

    /** [loadNearest] with every optional argument left out, for Java. */
    fun <T : Any> loadNearest(graphClass: Class<T>, vector: List<Float>, topK: Int): List<Scored<T>> =
        loadNearest(graphClass, vector, topK, null, null, null)

    /** [loadMatching] with every optional argument left out, for Java. */
    fun <T : Any> loadMatching(graphClass: Class<T>, query: String, topK: Int): List<Scored<T>> =
        loadMatching(graphClass, query, topK, 0.0)
}
