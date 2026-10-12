package org.drivine.test

import org.drivine.connection.Connection
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DatabaseType
import org.drivine.mapper.SubtypeRegistry
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.query.grammar.CypherGrammar
import org.drivine.schema.ConstraintManager
import org.drivine.schema.IndexManager
import org.drivine.schema.SchemaExecutor
import org.drivine.schema.SchemaGrammar
import java.util.Optional

/**
 * A persistence manager with no store behind it, for tests of what is sent to one. It keeps every
 * statement it is given with its parameters, apart by the way it came, and answers each with what
 * [answer] gives for it: rows to return, or a failure to throw.
 *
 * Schema statements run as they do on a store: on a connection of their own, through [indexes] and
 * [constraints], which are real managers over [ddl]. They are kept in [schema].
 *
 * @param ddl the grammar the schema managers write with
 * @param schemaGrammar what the manager says its schema grammar is; null for a manager that has none
 */
class RecordingStore(
    override val type: DatabaseType,
    ddl: SchemaGrammar = CypherDialect.NEO4J_5.schemaGrammar(),
    override val schemaGrammar: SchemaGrammar? = ddl,
    override val graphName: String? = null,
    override val supportsSchemaManagement: Boolean = true,
    private val answer: (Ran) -> Any? = { emptyList<Any?>() },
) : PersistenceManager {

    class Ran(val statement: String, val parameters: Map<String, Any?>) {
        override fun toString(): String = statement
    }

    /** What ran on a schema connection, in order. */
    val schema = mutableListOf<String>()

    /** What was run with [query], [getOne], [maybeGetOne] or [optionalGetOne], in order. */
    val queried = mutableListOf<Ran>()

    /** What was run with [execute], in order. */
    val executed = mutableListOf<Ran>()

    /** How many schema connections were opened, and how many of them were released. */
    var connected = 0
    var released = 0

    private fun answered(ran: Ran): List<Any?> = when (val outcome = answer(ran)) {
        is Throwable -> throw outcome
        null -> emptyList()
        else -> outcome as List<Any?>
    }

    private val provider = object : ConnectionProvider {
        override val name = "recording"
        override val type = this@RecordingStore.type
        override val subtypeRegistry: SubtypeRegistry? = null
        override val schemaGrammar: SchemaGrammar = ddl

        override fun connect(): Connection = object : Connection {
            init {
                connected++
            }

            override fun sessionId() = "recording"

            @Suppress("UNCHECKED_CAST")
            override fun <T : Any> query(spec: QuerySpecification<T>): List<T> {
                val text = spec.statement!!.text
                schema += text
                return answered(Ran(text, spec.parameters)) as List<T>
            }

            override fun startTransaction() = Unit
            override fun commitTransaction() = Unit
            override fun rollbackTransaction() = Unit
            override fun release(err: Throwable?) {
                released++
            }
        }

        override fun end() = Unit
    }

    override val database: String = "recording"
    override val grammar: CypherGrammar get() = CypherDialect.NEO4J_5.grammar()
    override val indexes: IndexManager = IndexManager(SchemaExecutor(provider), ddl)
    override val constraints: ConstraintManager = ConstraintManager(SchemaExecutor(provider), ddl, indexes)

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> query(spec: QuerySpecification<T>): List<T> {
        val ran = Ran(spec.statement!!.text, spec.parameters)
        queried += ran
        return answered(ran) as List<T>
    }

    override fun execute(spec: QuerySpecification<*>) {
        val ran = Ran(spec.statement!!.text, spec.parameters)
        executed += ran
        answered(ran)
    }

    override fun <T : Any> getOne(spec: QuerySpecification<T>): T = query(spec).single()

    override fun <T : Any> maybeGetOne(spec: QuerySpecification<T>): T? = query(spec).singleOrNull()

    override fun <T : Any> optionalGetOne(spec: QuerySpecification<T>): Optional<T> = Optional.ofNullable(maybeGetOne(spec))

    override fun registerSubtype(baseClass: Class<*>, labels: List<String>, subClass: Class<*>) =
        throw UnsupportedOperationException("The recording store maps nothing")
}
