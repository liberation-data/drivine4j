package org.drivine.schema

import org.drivine.DrivineException
import org.drivine.backend.sql.DdlContention
import org.drivine.connection.ReusedIdsException
import org.drivine.cypher.store.ddl.DdlStatement
import org.drivine.cypher.store.ddl.SqlStoreDdl
import org.drivine.cypher.store.plan.DatabaseReport
import org.drivine.cypher.store.plan.ItemCall
import org.drivine.cypher.store.plan.ItemReport
import org.drivine.cypher.store.plan.LogLevel
import org.drivine.cypher.store.plan.LogLine
import org.drivine.cypher.store.plan.Operation
import org.drivine.cypher.store.plan.PlanAction
import org.drivine.cypher.store.plan.PlanOutcome
import org.drivine.cypher.store.plan.PlanRequest
import org.drivine.cypher.store.plan.PlanStep
import org.drivine.cypher.store.plan.Purpose
import org.drivine.cypher.store.plan.Retry
import org.drivine.cypher.store.plan.SchemaPlanner
import org.drivine.cypher.store.schema.StoreError
import org.drivine.cypher.store.schema.StoreSchema
import org.drivine.manager.PersistenceManager
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.drivine.query.SqlStatement
import org.drivine.query.Statement
import org.drivine.query.transform
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * drivine4j's schema management as a driver over the module's schema planner ([SchemaPlanner],
 * `org.drivine.cypher.store.plan`): the planner decides what to read, make, drop, wait for and record, and what came of
 * it; this runs what it decides, where the spec's README ("The schema plan", "Running a round") says drivine4j runs it,
 * and acts on its report. [SchemaManager], [IndexManager] and [ConstraintManager] each build a [PlanRequest] and [run] it.
 *
 * What stays drivine4j's: the connections (DDL and listings on a schema connection of their own, through [executor],
 * which keeps Postgres's index builds apart; the marker's statements and the lowercased copies through the auto-commit
 * manager), running each action, telling the grammar the store's schema changed after DDL, the lowercased copies' read,
 * fill and record ([LowerCopyCatalog], a [LowerCopyRecord]), turning a driver's error into the module's [StoreError] ([StoreErrors]), and the
 * refusal of the graph ([ReusedIdsException]), which is thrown out of the round as it is.
 *
 * @param executor runs statements on a schema connection of their own
 * @param grammar the store's grammar, told of each DDL statement run ([SchemaGrammar.changed]), and the store the
 *   planner is told of ([storeSchema])
 * @param lowerCopies the record of lowercased copies, for an item that ignores case; null where there is no store to fill
 *   them in through
 * @param clock the library's clock, in milliseconds since the epoch, read once for each round
 * @param sleep waits the given milliseconds, before a poll
 */
internal class SchemaPlanDriver(
    private val executor: SchemaExecutor,
    private val grammar: SchemaGrammar,
    private val lowerCopies: () -> LowerCopyRecord?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {

    /** The errors each [StoreError] of the plan running on this thread was made from, for a [GrammarStoreSchema]'s rules. */
    private val thrown = ThreadLocal<Map<StoreError, Throwable>>()

    /** What the planner knows of the store: the module's, for drivine4j's own grammars. */
    val store: StoreSchema by lazy { grammar.storeSchema { error -> thrown.get()?.get(error) } }

    private val planner: SchemaPlanner by lazy { SchemaPlanner(store, markerStatements(store)) }

    /** Why [request] is refused before anything is applied, or null: see [SchemaPlanner.validate]. */
    fun validate(request: PlanRequest): String? = planner.validate(request)

    /**
     * One of the public [IndexManager] and [ConstraintManager] calls on [spec], under [policy] (a constraint's sample
     * size and whether it replaces an index in the way): planned, run, logged, and thrown where it failed.
     */
    fun item(call: ItemCall, spec: SchemaItemSpec, policy: SchemaPolicy = SchemaPolicy()): ItemReport {
        // A library's own refusal, before a statement reaches the store: it has nowhere to fill the copies in.
        if (call != ItemCall.DROP && spec.lowercased.isNotEmpty() && lowerCopies() == null) {
            throw DrivineException(
                "${spec.kind} ${spec.effectiveName} on ${spec.label}${spec.properties} ignores case, and this manager has no store to fill the lowercased copies in through."
            )
        }
        val report = run(PlanRequest(executor.database, Operation.Item(call, spec), policy = policy), manager = null)
        return checkNotNull(report.item) { "The plan of $call of $spec gave no item" }
    }

    /**
     * Plans [request] round by round, running each round's actions as [Execution] says, the marker's statements on
     * [manager], the auto-commit manager of the database; then logs the report's lines, and throws the store's error it
     * asks to be rethrown, or its failure. Returns the report where neither is given.
     */
    fun run(request: PlanRequest, manager: PersistenceManager?): DatabaseReport {
        val execution = Execution(manager)
        val outer = thrown.get()
        thrown.set(execution.thrown)
        try {
            // The round loop: each round's actions run in order, up to the first that fails, until the plan is done.
            var step = planner.start(request, clock())
            while (step is PlanStep.Run) {
                val outcomes = execution.round(step.actions)
                step = planner.resume(step.state, outcomes, clock())
            }
            execution.endPoll()
            val report = (step as PlanStep.Done).report
            act(report, execution)
            return report
        } finally {
            execution.endPoll()
            if (outer == null) thrown.remove() else thrown.set(outer)
        }
    }

    /** Logs [report]'s lines, each at its level under its logger, then throws what it says to throw. */
    private fun act(report: DatabaseReport, execution: Execution) {
        report.log.forEach(::log)
        report.rethrown?.let { rethrown ->
            val error = execution.errors.getOrNull(rethrown.round) ?: DrivineException(rethrown.error.messages)
            execution.unread.filter { it !== error }.forEach(error::addSuppressed)
            throw error
        }
        report.failure?.let { failure ->
            throw DrivineException(failure).also { e -> execution.unread.forEach(e::addSuppressed) }
        }
    }

    private fun log(line: LogLine) {
        val logger = loggers.getOrPut(line.logger) { LoggerFactory.getLogger(LOGGERS[line.logger] ?: SchemaPlanDriver::class.java) }
        when (line.level) {
            LogLevel.INFO -> logger.info(line.message)
            LogLevel.WARN -> logger.warn(line.message)
            LogLevel.ERROR -> logger.error(line.message)
        }
    }

    /** The action executor: one plan's rounds run against the store, and what each gave. */
    private inner class Execution(private val manager: PersistenceManager?) {

        /** The error each [StoreError] answered was made from. */
        val thrown = HashMap<StoreError, Throwable>()

        /** The error of each round's action that failed, by round; null for a round where none did. */
        val errors = mutableListOf<Throwable?>()

        /** Why a sample of the data couldn't be read, which the planner takes as no data: kept with what is thrown, as drivine4j kept it. */
        val unread = mutableListOf<Throwable>()

        /** Runs [actions] in order, stopping at the first that fails, and gives what came of each. */
        fun round(actions: List<PlanAction>): List<PlanOutcome> {
            val outcomes = mutableListOf<PlanOutcome>()
            var error: Throwable? = null
            for (action in actions) {
                val outcome = try {
                    answer(action)
                } catch (e: ReusedIdsException) {
                    // The graph itself is refused, for every owner alike: thrown out of the round as it is.
                    throw e
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                } catch (e: Exception) {
                    error = e
                    failed(action, e)
                }
                outcomes += outcome
                if (outcome is PlanOutcome.Failed) break
            }
            errors += error
            return outcomes
        }

        private fun failed(action: PlanAction, e: Exception): PlanOutcome.Failed {
            val error = StoreErrors.of(e)
            thrown[error] = e
            if (action is PlanAction.Query && action.purpose == Purpose.SAMPLE) unread += e
            return PlanOutcome.Failed(error)
        }

        private fun answer(action: PlanAction): PlanOutcome {
            polls.before(action)
            return when (action) {
                is PlanAction.Query -> {
                    if (action.delayMillis > 0) sleep(action.delayMillis)
                    PlanOutcome.Rows(query(action)).also { polls.after(action, it.rows) }
                }
                is PlanAction.Execute -> {
                    execute(action)
                    polls.after(action)
                    PlanOutcome.Ran
                }
                is PlanAction.AwaitIndex -> {
                    executor.query(action.statement, action.parameters)
                    PlanOutcome.Ran
                }
                is PlanAction.ReadLowerCopies -> PlanOutcome.Rows(copies().stored().map { (label, property) -> listOf(label, property) })
                is PlanAction.FillLowerCopies -> {
                    // The planner has left out the copies recorded already: those given are filled in, recorded or not.
                    copies().fillIn(action.label, action.properties, again = true)
                    PlanOutcome.Ran
                }
                is PlanAction.WriteMarker -> {
                    markerExecute(action.statement, action.parameters)
                    PlanOutcome.Ran
                }
            }
        }

        /** A listing, an index's state or a sample on a schema connection; a marker's read on the auto-commit manager. */
        private fun query(action: PlanAction.Query): List<Any?> = when (action.purpose) {
            Purpose.MARKER, Purpose.SCOPES -> {
                val statement: Statement = when {
                    action.compiled -> CypherStatement(action.statement)
                    // The scopes are read in the store's SQL where it has one for them (SQLite), and as Cypher elsewhere.
                    action.purpose == Purpose.SCOPES -> if (store.ddl is SqlStoreDdl) SqlStatement(action.statement) else CypherStatement(action.statement)
                    else -> Statement(action.statement)
                }
                val manager = requireManager()
                val spec = QuerySpecification.withStatement(statement).bind(action.parameters).transform(String::class.java)
                SchemaStatementTrace.traced(manager, spec) { manager.query(spec) }
            }
            else -> executor.query(action.statement, action.parameters)
        }

        /** DDL on a schema connection, the grammar told after it however it went; a marker's statement on the auto-commit manager. */
        private fun execute(action: PlanAction.Execute) {
            when (action.purpose) {
                Purpose.MARKER -> markerExecute(statementText(action.statement), action.parameters)
                Purpose.PREPARE -> executor.execute(schemaStatement(action.statement))
                else -> try {
                    when (action.retry) {
                        Retry.ONCE -> executor.execute(schemaStatement(action.statement))
                        Retry.CONTENDED -> DdlContention.whenNotContended { executor.execute(schemaStatement(action.statement)) }
                    }
                } finally {
                    grammar.changed()
                }
            }
        }

        private fun markerExecute(statement: String, parameters: Map<String, Any?>) {
            val manager = requireManager()
            val spec = QuerySpecification.withStatement(statement).bind(parameters)
            SchemaStatementTrace.traced(manager, spec) { manager.execute(spec) }
        }

        private fun statementText(statement: DdlStatement): String = when (statement) {
            is DdlStatement.Text -> statement.statement
            is DdlStatement.Native -> statement.args.joinToString(" ")
        }

        private fun copies(): LowerCopyRecord =
            lowerCopies() ?: throw DrivineException("The lowercased copies can't be read or filled in: this manager has no store to fill the lowercased copies in through.")

        private fun requireManager(): PersistenceManager =
            manager ?: throw DrivineException("The schema marker's statements need the database's auto-commit manager, and this plan was given none.")

        private val polls = Polls()

        fun endPoll() = polls.end()
    }

    /**
     * Where the planner's rounds poll a store that builds or drops in the background (FalkorDB), told to a
     * [SchemaStatementTrace] observer as one poll, as drivine4j's loops were: a build after the find of an index just
     * made, a drop after an index's drop, and a constraint's build after its create, or of one found being built. A run
     * of rounds that poll one listing is one poll, its outcome read from the last listing.
     */
    private inner class Polls {
        private inner class Poll(val what: String, val statement: String, val anyDelay: Boolean, val label: String?, var rows: List<Any?>?)

        private var open: Poll? = null

        /** What the last DDL left to come: the find of an index made, a drop's poll, or a constraint's build. */
        private var next: String? = null
        private var label: String? = null

        /** The constraints last listed, to find one still being built. */
        private var constraintsBuilding = false

        private val async: Boolean get() = store.indexOperationsAreAsync || store.constraintCreationIsAsync

        fun before(action: PlanAction) {
            if (!async) return
            val listing = (action as? PlanAction.Query)?.takeIf { it.purpose == Purpose.LISTING }
            val poll = open
            if (poll != null) {
                if (listing != null && listing.statement == poll.statement && (poll.anyDelay || listing.delayMillis > 0)) return
                end()
            }
            if (listing == null) return
            val constraints = listing.statement == store.ddl.listConstraintsQuery()
            when {
                next == INDEX_DROP && !constraints -> start(INDEX_DROP, listing.statement, anyDelay = true)
                next == CONSTRAINT_BUILD && constraints -> start(CONSTRAINT_BUILD, listing.statement, anyDelay = true)
                constraints && constraintsBuilding -> start(CONSTRAINT_BUILD, listing.statement, anyDelay = true)
                listing.delayMillis > 0 -> start(if (constraints) CONSTRAINT_BUILD else INDEX_BUILD, listing.statement, anyDelay = false)
            }
            if (next != INDEX_FIND) next = null
        }

        fun after(action: PlanAction.Query, rows: List<Any?>) {
            if (!async || action.purpose != Purpose.LISTING) return
            val constraints = action.statement == store.ddl.listConstraintsQuery()
            open?.let { it.rows = rows }
            if (constraints) constraintsBuilding = runCatching { store.parseConstraintRows(rows) }.getOrDefault(emptyList()).any { it.buildInProgress }
            if (next == INDEX_FIND && !constraints) {
                // drivine4j polled the build of an index it made once the find after it was read.
                next = null
                start(INDEX_BUILD, action.statement, anyDelay = false)
                open?.rows = rows
            }
        }

        fun after(action: PlanAction.Execute) {
            if (!async || action.purpose != Purpose.DDL) return
            val (text, native) = when (val statement = action.statement) {
                is DdlStatement.Text -> statement.statement to false
                is DdlStatement.Native -> statement.args.joinToString(" ") to true
            }
            val words = text.trim().uppercase()
            val constraint = native || "CONSTRAINT" in words
            label = text
            next = when {
                constraint && "CREATE" in words.take(PREFIX) && store.constraintCreationIsAsync -> CONSTRAINT_BUILD
                !constraint && words.startsWith("CREATE") && store.indexOperationsAreAsync -> INDEX_FIND
                !constraint && words.startsWith("DROP") && store.indexOperationsAreAsync -> INDEX_DROP
                else -> null
            }
        }

        private fun start(what: String, statement: String, anyDelay: Boolean) {
            open = Poll(what, statement, anyDelay, label, null)
            executor.pollStarted(what)
        }

        /** Ends the poll open, if one is, with what its last listing says of it. */
        fun end() {
            val poll = open ?: return
            open = null
            executor.pollEnded(poll.what, outcome(poll))
        }

        private fun outcome(poll: Poll): String {
            if (poll.what == INDEX_DROP) return "dropped"
            val rows = poll.rows ?: return if (poll.what == CONSTRAINT_BUILD) "absent" else "still building"
            val listed = runCatching { if (poll.what == CONSTRAINT_BUILD) store.parseConstraintRows(rows) else store.parseIndexRows(rows) }.getOrDefault(emptyList())
            // The items of the label the last DDL named, where it named one listed; else every item listed.
            val items = listed.filter { poll.label?.contains(it.label) == true }.ifEmpty { listed }
            return when {
                poll.what == CONSTRAINT_BUILD && items.isEmpty() -> "absent"
                items.any { it.buildFailed } -> "failed"
                items.any { it.buildInProgress } -> if (poll.what == CONSTRAINT_BUILD) "failed: ${DrivineException::class.java.name}" else "still building"
                else -> "built"
            }
        }
    }

    private val loggers = java.util.concurrent.ConcurrentHashMap<String, Logger>()

    private companion object {
        /** The classes the planner's log lines are logged under, by the name each line gives. */
        val LOGGERS: Map<String, Class<*>> = mapOf(
            "SchemaManager" to SchemaManager::class.java,
            "IndexManager" to IndexManager::class.java,
            "ConstraintManager" to ConstraintManager::class.java,
        )

        const val INDEX_BUILD = "index build"
        const val INDEX_DROP = "index drop"
        const val INDEX_FIND = "index find"
        const val CONSTRAINT_BUILD = "constraint build"

        /** How much of a native command's text names what it does (`GRAPH.CONSTRAINT CREATE`). */
        const val PREFIX = 24
    }
}
