package org.drivine.cli

import java.io.File
import java.io.IOException

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
    private val open: (File) -> java.io.Reader,
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
     */
    fun run(assignments: List<Assignment>, inputs: Iterator<Input>): Int {
        for (assignment in assignments) {
            val code = assign(assignment)
            if (code != ExitCode.OK) return code
        }
        return when (val outcome = inputs(inputs, transaction = false)) {
            is Outcome.Stopped -> if (outcome.code == Cli.DONE) finished() else outcome.code
            else -> finished()
        }
    }

    private fun finished(): Int = if (failed) ExitCode.STATEMENT_FAILED else ExitCode.OK

    /** Runs [inputs] until they end or something ends them; [transaction] says whether one is open. */
    private fun inputs(inputs: Iterator<Input>, transaction: Boolean): Outcome {
        for (input in inputs) {
            val outcome = when (input) {
                is Input.Statement -> handled(statement(input.cypher), transaction)
                is Input.Command -> command(input, inputs, transaction)
            }
            if (outcome != null) return outcome
        }
        return Outcome.Ended
    }

    /** Runs [command], which may read on in [inputs], as `:begin` does: null where the run goes on. */
    private fun command(command: Input.Command, inputs: Iterator<Input>, transaction: Boolean): Outcome? {
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
            ":param" -> handled(
                Assignment.parse(rest)?.let(::assign)
                    ?: out.fail(ExitCode.STATEMENT_FAILED, "drivine: :param takes NAME => EXPRESSION, NAME=EXPRESSION or a map, {NAME: EXPRESSION}"),
                transaction,
            )
            ":params" -> handled(
                when (rest) {
                    "" -> out.print { PlainRenderer.parameters(parameters, it) }
                    "clear" -> ExitCode.OK.also { parameters.clear() }
                    else -> out.fail(ExitCode.STATEMENT_FAILED, "drivine: :params takes nothing, or clear")
                },
                transaction,
            )
            ":source" -> source(rest, transaction)
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
    private fun begin(inputs: Iterator<Input>): Outcome? = try {
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
    } catch (e: Exception) {
        // The store refused to begin or to commit it.
        out.trace(e)
        handled(out.fail(ExitCode.STATEMENT_FAILED, e.message ?: e.javaClass.name), transaction = false)
    }

    /** Runs the inputs of the file at [path], from the working directory where it's relative, in place. */
    private fun source(path: String, transaction: Boolean): Outcome? {
        if (path.isEmpty()) return handled(out.fail(ExitCode.STATEMENT_FAILED, "drivine: :source takes a file"), transaction)
        val file = File(path).let { if (it.isAbsolute) it else File(dir, path) }
        val reader = try {
            if (!file.isFile) throw IOException("no such file")
            open(file)
        } catch (e: IOException) {
            return handled(out.fail(ExitCode.STATEMENT_FAILED, "drivine: can't read :source $path: ${e.message}"), transaction)
        }
        return reader.use {
            // Its end is only its own: the input that named it reads on.
            when (val outcome = inputs(Statements(it), transaction)) {
                Outcome.Ended -> null
                else -> outcome
            }
        }
    }

    /** Evaluates [assignment] on the store, as `RETURN expression`, and sets what it names: [ExitCode.OK], or the code it failed with. */
    private fun assign(assignment: Assignment): Int {
        val value = try {
            store.run(assignment.cypher, parameters).rows.single().single()
        } catch (e: Unreachable) {
            return unreachable(e)
        } catch (e: Exception) {
            out.trace(e)
            return out.fail(ExitCode.STATEMENT_FAILED, e.message ?: e.javaClass.name)
        }
        when (assignment) {
            is Assignment.Named -> parameters[assignment.name] = value
            is Assignment.Map -> {
                if (value !is Map<*, *>) return out.fail(ExitCode.STATEMENT_FAILED, "drivine: a parameter map is a map, as in {limit: 10}")
                value.forEach { (name, item) -> parameters[name.toString()] = item }
            }
        }
        return ExitCode.OK
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

    /** What [code], a statement's or a command's, does to the run: null where it goes on. */
    private fun handled(code: Int, transaction: Boolean): Outcome? = when (code) {
        ExitCode.OK -> null
        ExitCode.STATEMENT_FAILED -> {
            failed = true
            when {
                !continueOnFailure -> Outcome.Stopped(code)
                transaction -> Outcome.Aborted
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
