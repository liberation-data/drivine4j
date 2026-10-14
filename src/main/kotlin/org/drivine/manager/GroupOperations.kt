package org.drivine.manager

import java.math.BigDecimal
import java.util.function.Function
import kotlin.reflect.KProperty1
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.TransformPostProcessor
import org.drivine.model.FragmentModel
import org.drivine.model.Stamps
import org.drivine.query.FragmentQueryBuilder
import org.drivine.query.GroupQuery
import org.drivine.query.fieldStoredAs
import org.drivine.query.dsl.Aggregate
import org.drivine.query.dsl.GroupColumn
import org.drivine.query.dsl.GroupFunction
import org.drivine.query.dsl.GroupQuerySpec
import org.drivine.query.dsl.GroupRow
import org.drivine.query.dsl.GroupRows
import org.drivine.query.dsl.Key
import org.drivine.query.dsl.KeySource
import org.drivine.query.dsl.NumberKind
import org.drivine.query.dsl.PropertyReference

/**
 * Grouped reads: the nodes of one fragment put in groups by a key, with a count, a sum or another
 * aggregate for each group. Reached as [GraphObjectOperations.groups].
 *
 * Everything else on the manager returns the objects it maps. This returns summaries of them, made
 * by the store in one statement, which reads the same on every engine: nothing is loaded to be
 * tallied here.
 */
@Suppress("DEPRECATION") // built on GraphObjectManager, which is deprecated for callers
class GroupOperations internal constructor(
    private val manager: GraphObjectManager,
    private val persistenceManager: PersistenceManager,
) {

    private val backend = manager.backend

    /**
     * Groups the [graphClass] nodes as [spec] says, and gives what its `rows` makes of each group.
     *
     * ```kotlin
     * val ratings: List<PageVotes> = graphObjectManager.groups.load<FeedbackData, _> {
     *     val page  = key(query.page)
     *     val votes = count()
     *     val likes = count { query.helpful eq true }
     *     having { votes gte 3 }
     *     orderBy { votes.desc() }
     *     rows { PageVotes(it[page], it[votes], it[likes]) }
     * }
     * ```
     *
     * The generated `load<T, R> { }` passes [queryObject] for you. A group exists because a node is in
     * it: no group has no members. A condition can go in three places, and each asks something else:
     * `where` says which nodes are read at all, an aggregate's filter which of a group's nodes count
     * toward that one number, and `having` which groups are returned.
     *
     * The groups come back in the order asked for, and then by key, so groups that tie are in one
     * order on every engine.
     *
     * @param graphClass the `@NodeFragment` whose nodes are grouped
     * @param queryObject the fragment's query object, which the block reads its properties from
     * @throws IllegalArgumentException before anything is sent, when [graphClass] is a view, the block
     *   declares no key, `key` is given a property declared nullable or one that holds several values,
     *   or a condition names anything but the grouped nodes' own properties
     */
    @JvmSynthetic // Java has the overload below: a Java lambda would fit both, and name neither.
    fun <T : Any, Q : Any, R> load(graphClass: Class<T>, queryObject: Q, spec: GroupQuerySpec<Q>.() -> GroupRows<R>): List<R> {
        val query = GroupQuerySpec(queryObject)
        val rows = query.spec()
        query.closed = true
        val statement = backend.groups(graphClass, query)
        val columns: List<GroupColumn<*>> = query.keys + query.aggregates
        val reader = Reader(FragmentModel.from(graphClass))
        return persistenceManager.query(statement.spec().transform(Map::class.java)).map { row ->
            @Suppress("UNCHECKED_CAST")
            rows.make(GroupRow(columns, row as Map<String, Any?>, reader::read))
        }
    }

    /** [load] for Java, whose lambda is a function: `groups.load(Feedback.class, dsl, spec -> { …; return spec.rowsOf(row -> …); })`. */
    fun <T : Any, Q : Any, R> load(graphClass: Class<T>, queryObject: Q, spec: Function<GroupQuerySpec<Q>, GroupRows<R>>): List<R> =
        load(graphClass, queryObject) { spec.apply(this) }

    /**
     * How many [graphClass] nodes have each value of the field [field]: one entry for each value, in
     * the order of the values. The nodes with no value are counted under null, where the field may be null.
     *
     * It takes no filter: a count of some of the nodes is [load] with a `where`.
     *
     * @param field the name of the field, as the class declares it
     */
    fun <T : Any> countBy(graphClass: Class<T>, field: String): Map<Any?, Long> {
        val declared = FragmentModel.from(graphClass).fields.firstOrNull { it.name == field }
            ?: throw IllegalArgumentException("${graphClass.simpleName} has no field '$field' to count by.")
        val counted = load(graphClass, Unit) {
            // The stamp's own name is held quoted in the model, and a reference names a property unquoted.
            val property = PropertyReference<Any?>(FragmentQueryBuilder.NODE_ALIAS, if (declared.stamp) Stamps.PROPERTY else declared.propertyName)
            // A field a Kotlin class declares never null is read as one, so a node without it is reported, and is no null in a map of non-null keys.
            val key = if (declared.nullable || !graphClass.isAnnotationPresent(Metadata::class.java)) keyOrNull(property) else key(property)
            val count = count()
            rows { it[key] to it[count] }
        }
        return counted.toMap(LinkedHashMap())
    }

    /** Reads the columns of one grouped read of [source], as their handles type them. */
    private inner class Reader(private val source: FragmentModel) {

        /** What hydrates the nodes of a path key's class, made once for a read. */
        private val hydrators = HashMap<Class<*>, TransformPostProcessor<Any, Any>>()

        /** The value of [column] as its handle types it, from the [row] the store gave. */
        fun read(column: GroupColumn<*>, row: Map<String, Any?>): Any? {
            val value = row[column.column]
            return when (column) {
                is Key<*> -> when (val from = column.source) {
                    is KeySource.Property -> {
                        val read = text(column, row) ?: declared(from.propertyName, value)
                        check(read != null || column.nullable) {
                            "A ${source.clazz.simpleName} has no value for '${from.propertyName}', which key(…) reads as never null. Use keyOrNull(…)."
                        }
                        read
                    }
                    is KeySource.Path -> node(from.target, requireNotNull(value) { "A group of ${source.clazz.simpleName} came back with no ${from.target.simpleName} for its key." })
                }
                is Aggregate<*> -> when {
                    value == null -> null
                    column.number == NumberKind.WHOLE -> (value as Number).toLong()
                    column.number == NumberKind.FRACTIONAL -> (value as Number).toDouble()
                    else -> declared(requireNotNull(column.propertyName), value)
                }
            }
        }

        /**
         * What Oracle's reading of a text key leaves to the columns beside it: `""` for a property
         * stored empty, which comes back as null, and the number of a property no field declares.
         * Null where neither column says anything, as on a graph engine, which has neither.
         */
        private fun text(key: Key<*>, row: Map<String, Any?>): Any? {
            (row["${key.column}${GroupQuery.NUMBER}"] as? Number)?.let { number ->
                // A whole number is a Long, as the graph engines give one.
                return BigDecimal(number.toString()).let { if (it.stripTrailingZeros().scale() <= 0) it.toLong() else it.toDouble() }
            }
            return if (row[key.column] == null && (row["${key.column}${GroupQuery.EMPTY}"] as? Number)?.toInt() == 1) "" else null
        }

        /** [value] as the field stored as [propertyName] declares it; as it came, when no field does. */
        private fun declared(propertyName: String, value: Any?): Any? {
            val type = source.fieldStoredAs(propertyName)?.type ?: return value
            if (value == null || type.kotlin.javaObjectType.isInstance(value)) return value
            return Neo4jObjectMapper.instance.convertValue(value, type)
        }

        /** The node a key is, as a load of [target] hydrates it: its subtype, its renamed properties, its bag and its labels. */
        private fun node(target: Class<*>, value: Any): Any {
            val hydrator = hydrators.getOrPut(target) {
                manager.autoRegisterSubtypesIfNeeded(target)
                @Suppress("UNCHECKED_CAST")
                TransformPostProcessor<Any, Any>(target as Class<Any>, manager.subtypeRegistry)
            }
            val hydrated = hydrator.apply(listOf(value)).first()
            @Suppress("UNCHECKED_CAST")
            manager.snapshotResults(target as Class<Any>, listOf(hydrated))
            return hydrated
        }
    }
}

/**
 * How many [T] nodes have each value of [property]: `groups.countBy(FeedbackData::page)`. See
 * [GroupOperations.countBy].
 */
inline fun <reified T : Any, K> GroupOperations.countBy(property: KProperty1<T, K>): Map<K, Long> {
    @Suppress("UNCHECKED_CAST")
    return countBy(T::class.java, property.name) as Map<K, Long>
}
