package org.drivine.cli

import java.io.File
import java.io.IOException
import java.io.Reader

/** How a [Session] prints: the [Cli]'s stdout and stderr, and its exit codes, so a session writes as a run does. */
internal interface Output {
    /** Writes to stdout with [write]: [ExitCode.OK], [ExitCode.STATEMENT_FAILED] where it couldn't, or [Cli.DONE] where stdout was closed. */
    fun print(write: (Appendable) -> Unit): Int

    /** Writes [message] to stderr, as it comes, and gives [code]. */
    fun fail(code: Int, message: String): Int

    /** Writes [message], a warning that fails nothing, to stderr. */
    fun warn(message: String)

    /** [e]'s stack trace, where the run asks for it. */
    fun trace(e: Throwable)
}

/**
 * One run's statements and commands against one [store], as the contract's "Files" and "Parameters" say: the parameters
 * set so far, which every statement is given; `:begin` to `:commit` or `:rollback` in one transaction of the store's; a
 * `:source` file's inputs run in place; and the run stopped at the first failure, unless [continueOnFailure].
 */
internal class Session(
    private val store: Store,
    private val renderer: Renderer,
    private val out: Output,
    private val dir: File,
    private val continueOnFailure: Boolean,
    /** Opens a `:source` file's text as the run reads a `-f` file's, its byte-order mark left out. */
    private val open: (File) -> Reader,
) {
    private val parameters = LinkedHashMap<String, Any?>()
    private var failed = false

    /** How [inputs] ended, which says what the run does next. */
    private sealed interface Outcome {
        /** The input ended. */
        data object Ended : Outcome

        /** `:exit` or `:quit`: no more is to be read. */
        data object Exited : Outcome

        /** The run is to end, with [code]. */
        data class Stopped(val code: Int) : Outcome

        /** In a transaction: `:commit`. */
        data object Committed : Outcome

        /** In a transaction: `:rollback`. */
        data object RolledBack : Outcome

        /** In a transaction, under `--continue`: a statement failed, so the transaction is rolled back and closed. */
        data object Aborted : Outcome
    }

    /** Thrown out of a transaction's block to roll it back, with how its inputs ended. */
    private class Rollback(val outcome: Outcome) : RuntimeException(null, null, false, false)

    /**
     * Evaluates [assignments], the `-P` parameters, then runs [inputs], and gives the run's exit code. A `-P` that fails
     * ends the run with [ExitCode.STATEMENT_FAILED] before any statement runs, with `--continue` too: they'd run without it.
     * [file] is the `-f` file that [inputs] are read from, where they are, so that a `:source` of it fails as one of a
     * file being read does.
     */
    fun run(assignments: List<Assignment>, inputs: Iterator<Input>, file: File? = null): Int {
        for (assignment in assignments) {
            val (code, _) = assign(assignment)
            if (code != ExitCode.OK) return code
        }
        return Sources(inputs, file?.canonicalFile).use { sources ->
            when (val outcome = inputs(sources, transaction = false)) {
                is Outcome.Stopped -> if (outcome.code == Cli.DONE) finished() else outcome.code
                else -> finished()
            }
        }
    }

    /**
     * The inputs the run reads: the run's own, from [file] where they are a `-f` file's, and before them each `:source`
     * file being read, the one named last first.
     * So a file's inputs run in place, as though its text stood where `:source` names it: a `:begin` in it opens a
     * transaction that the inputs after the file run in, and a `:commit` in it commits the one open. A file is closed at
     * its end, and those still open when the run ends are closed then.
     */
    private class Sources(run: Iterator<Input>, file: File?) : Iterator<Input>, AutoCloseable {
        private class Source(val file: File?, val inputs: Iterator<Input>, val reader: Reader?)

        private val open = ArrayDeque(listOf(Source(file, run, null)))

        override fun hasNext(): Boolean {
            while (!open.last().inputs.hasNext()) {
                if (open.size == 1) return false
                open.removeLast().reader?.close()
            }
            return true
        }

        override fun next(): Input {
            if (!hasNext()) throw NoSuchElementException()
            return open.last().inputs.next()
        }

        /** Whether [file], canonical, is being read, so that reading it again from inside itself would never end. */
        fun reading(file: File): Boolean = open.any { it.file == file }

        /** Reads [file], canonical, from [reader] before the rest. */
        fun push(file: File, reader: Reader) {
            open.addLast(Source(file, Statements(reader), reader))
        }

        override fun close() {
            while (open.size > 1) open.removeLast().reader?.close()
        }
    }

    private fun finished(): Int = if (failed) ExitCode.STATEMENT_FAILED else ExitCode.OK

    /** Runs [inputs] until they end or something ends them; [transaction] says whether one is open. */
    private fun inputs(inputs: Sources, transaction: Boolean): Outcome {
        for (input in inputs) {
            val outcome = when (input) {
                is Input.Statement -> handled(statement(input.cypher), transaction, statement = true)
                is Input.Command -> command(input, inputs, transaction)
            }
            if (outcome != null) return outcome
        }
        return Outcome.Ended
    }

    /** Runs [command], which may read on in [inputs], as `:begin` does: null where the run goes on. */
    private fun command(command: Input.Command, inputs: Sources, transaction: Boolean): Outcome? {
        val rest = command.line.trim().removePrefix(command.name).trim()
        return when (command.name) {
            ":exit", ":quit" -> Outcome.Exited
            ":begin" -> when {
                !store.transactions -> {
                    out.warn(FALKORDB_BEGIN)
                    null
                }
                transaction -> handled(out.fail(ExitCode.STATEMENT_FAILED, "drivine: a transaction is open already: :commit or :rollback it first"), transaction)
                else -> begin(inputs)
            }
            ":commit" -> when {
                transaction -> Outcome.Committed
                // FalkorDB's statements have each committed already, so there's nothing to commit and nothing fails.
                !store.transactions -> null
                else -> handled(out.fail(ExitCode.STATEMENT_FAILED, "drivine: no transaction is open: :begin opens one"), transaction)
            }
            ":rollback" -> when {
                transaction -> Outcome.RolledBack
                !store.transactions -> handled(out.fail(ExitCode.STATEMENT_FAILED, FALKORDB_ROLLBACK), transaction)
                else -> handled(out.fail(ExitCode.STATEMENT_FAILED, "drivine: no transaction is open: :begin opens one"), transaction)
            }
            // A :param whose expression the store evaluates fails as a statement does; one of none of the forms reaches
            // no store, and fails as a command does, leaving the transaction open.
            ":param" -> when (val assignment = Assignment.parse(rest)) {
                null -> handled(out.fail(ExitCode.STATEMENT_FAILED, "drivine: :param takes NAME => EXPRESSION, NAME=EXPRESSION or a map, {NAME: EXPRESSION}"), transaction)
                // A :param is a statement where the store failed to evaluate it: then its transaction may be unusable.
                else -> assign(assignment).let { (code, storeFailed) -> handled(code, transaction, statement = storeFailed) }
            }
            ":params" -> handled(
                when (rest) {
                    "" -> out.print { PlainRenderer.parameters(parameters, it) }
                    "clear" -> ExitCode.OK.also { parameters.clear() }
                    else -> out.fail(ExitCode.STATEMENT_FAILED, "drivine: :params takes nothing, or clear")
                },
                transaction,
            )
            ":source" -> source(rest, inputs, transaction)
            ":help" -> handled(out.print { it.append(HELP) }, transaction)
            in LATER -> handled(out.fail(ExitCode.STATEMENT_FAILED, "drivine: ${command.name} comes later, and this build doesn't run it"), transaction)
            else -> handled(out.fail(ExitCode.STATEMENT_FAILED, "Could not find command ${command.name}, use :help to see available commands"), transaction)
        }
    }

    /**
     * Runs [inputs] in a transaction until `:commit`, which commits it, or `:rollback`, the input's end, or a failure,
     * which roll it back. Null where the run goes on after it: after `:commit` or `:rollback`, and, under `--continue`,
     * after a failure, with the statements after it run on their own, as no transaction is open then.
     */
    private fun begin(inputs: Sources): Outcome? = try {
        store.transaction {
            val outcome = inputs(inputs, transaction = true)
            if (outcome != Outcome.Committed) throw Rollback(outcome)
        }
        null
    } catch (e: Rollback) {
        when (e.outcome) {
            Outcome.RolledBack, Outcome.Aborted -> null
            // The input ended inside the transaction, which is rolled back, as cypher-shell does, and the run doesn't fail for it.
            else -> e.outcome
        }
    } catch (e: Unreachable) {
        Outcome.Stopped(unreachable(e))
    } catch (e: IOException) {
        // The input couldn't be read on: the transaction is rolled back, and the run ends as it would outside one.
        throw e
    } catch (e: Exception) {
        // The store refused to begin or to commit it.
        out.trace(e)
        handled(out.fail(ExitCode.STATEMENT_FAILED, e.message ?: e.javaClass.name), transaction = false)
    }

    /**
     * Reads the inputs of the file at [path], from the working directory where it's relative, before the rest of
     * [inputs], so they run in place. A file that is being read already, as one that sources itself, fails, as it would
     * never end.
     */
    private fun source(path: String, inputs: Sources, transaction: Boolean): Outcome? {
        if (path.isEmpty()) return handled(out.fail(ExitCode.STATEMENT_FAILED, "drivine: :source takes a file"), transaction)
        val file = File(path).let { if (it.isAbsolute) it else File(dir, path) }
        try {
            if (!file.isFile) throw IOException("no such file")
            val canonical = file.canonicalFile
            if (inputs.reading(canonical)) {
                return handled(out.fail(ExitCode.STATEMENT_FAILED, "drivine: :source $path is being read already, so it would never end"), transaction)
            }
            inputs.push(canonical, open(file))
        } catch (e: IOException) {
            return handled(out.fail(ExitCode.STATEMENT_FAILED, "drivine: can't read :source $path: ${e.message}"), transaction)
        }
        return null
    }

    /**
     * Evaluates [assignment] on the store, as `RETURN expression`, and sets what it names: [ExitCode.OK], or the code it
     * failed with; and whether the store failed to evaluate it, which may leave its transaction unusable.
     */
    private fun assign(assignment: Assignment): Pair<Int, Boolean> {
        val value = try {
            store.run(assignment.cypher, parameters).rows.single().single()
        } catch (e: Unreachable) {
            return unreachable(e) to true
        } catch (e: Exception) {
            out.trace(e)
            return out.fail(ExitCode.STATEMENT_FAILED, e.message ?: e.javaClass.name) to true
        }
        when (assignment) {
            is Assignment.Named -> parameters[assignment.name] = value
            is Assignment.Map -> {
                // The store evaluated it, and its transaction is as it was: only what it gave isn't a map.
                if (value !is Map<*, *>) return out.fail(ExitCode.STATEMENT_FAILED, "drivine: a parameter map is a map, as in {limit: 10}") to false
                value.forEach { (name, item) -> parameters[name.toString()] = item }
            }
        }
        return ExitCode.OK to false
    }

    /** Runs one statement and prints what it gives: [ExitCode.OK], or the code it failed with, or [Cli.DONE] where stdout was closed. */
    private fun statement(cypher: String): Int {
        val result = try {
            store.run(cypher, parameters)
        } catch (e: Unreachable) {
            return unreachable(e)
        } catch (e: Exception) {
            out.trace(e)
            // The store's message, or the compiler's refusal: a statement that failed prints nothing to stdout.
            return out.fail(ExitCode.STATEMENT_FAILED, e.message ?: e.javaClass.name)
        }
        return out.print { renderer.render(result, it) }
    }

    /**
     * What [code], a statement's or a command's, does to the run: null where it goes on. Under `--continue`, a failed
     * [statement] (a `:param` is one, as the store evaluates it) rolls back the transaction open, and a failed command
     * leaves it open.
     */
    private fun handled(code: Int, transaction: Boolean, statement: Boolean = false): Outcome? = when (code) {
        ExitCode.OK -> null
        ExitCode.STATEMENT_FAILED -> {
            failed = true
            when {
                !continueOnFailure -> Outcome.Stopped(code)
                // A statement that failed, or a :param whose expression did, may leave the store's transaction unusable,
                // as Postgres's is after a failure; a command that fails reaches no store, and leaves it as it is.
                transaction && statement -> Outcome.Aborted
                else -> null
            }
        }
        else -> Outcome.Stopped(code)
    }

    private fun unreachable(e: Unreachable): Int {
        out.trace(e)
        return out.fail(ExitCode.UNREACHABLE, "drivine: can't connect to the ${store.type.flag} store: ${e.message}")
    }

    companion object {
        /** The contract's commands that this build doesn't run yet. */
        internal val LATER = setOf(":use")

        /** The contract's warning, word for word, where `:begin` opens no transaction, on FalkorDB. */
        const val FALKORDB_BEGIN = "FalkorDB has no multi-statement transactions; each statement runs and commits on its own"

        const val FALKORDB_ROLLBACK = "drivine: FalkorDB has no multi-statement transactions, so there is nothing to roll back: each statement has committed"

        /** What `:help` prints. */
        val HELP = """
            |:begin                       Opens a transaction, which the statements after it run in.
            |:commit                      Commits it.
            |:rollback                    Rolls it back.
            |:param NAME => EXPRESSION    Sets a parameter. Also :param NAME=EXPRESSION and :param {NAME: EXPRESSION, …}.
            |:params                      Prints the parameters. :params clear clears them.
            |:source FILE                 Runs a file's statements in place.
            |:exit, :quit                 Stops reading.
            |:help                        Prints this.
            |""".trimMargin()
    }
}
