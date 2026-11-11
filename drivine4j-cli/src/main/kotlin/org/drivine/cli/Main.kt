package org.drivine.cli

import java.io.BufferedReader
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
<<<<<<< HEAD
import java.io.PushbackReader
=======
>>>>>>> origin/main
import java.io.Reader
import java.io.StringReader
import java.io.Writer
import java.util.Properties
import kotlin.system.exitProcess

/**
 * The `drivine` command: Cypher statements against one store, each result printed as it comes, and an exit code that says
 * how it went.
 *
 * The CLI is thin: it reads the arguments, makes connection properties of the address, runs the statement through
 * Drivine as an application would ([Store]), and prints what it gives. Its contract, flags, output and exit codes, is
 * drivine-cypher-spec's `cli/README.md`, which every build of the CLI meets.
 */
fun main(args: Array<String>) {
    // The CLI logs nothing until --log is here, so SLF4J, finding no logger, isn't to say so on stderr.
    System.setProperty("slf4j.internal.verbosity", "ERROR")
    // Netty's pooled allocator, where nothing else chose one, for the Neo4j driver on Neo4j and Memgraph. The driver
    // turns Netty's use of Unsafe off in a native image (-Dio.netty.noUnsafe=true), and Netty 4.2's default, adaptive
    // allocator then fails each buffer with a NullPointerException in PlatformDependent0.safeConstructPutInt, so the Bolt
    // handshake is never written. The pooled one needs no Unsafe. Set before anything reaches Netty.
    if (System.getProperty("io.netty.allocator.type") == null) System.setProperty("io.netty.allocator.type", "pooled")
    val out = OutputStreamWriter(FileOutputStream(FileDescriptor.out), Charsets.UTF_8)
    val err = OutputStreamWriter(FileOutputStream(FileDescriptor.err), Charsets.UTF_8)
    // Cli.run flushes both streams.
    exitProcess(Cli(System.getenv(), out, err, stdin = { InputStreamReader(System.`in`, Charsets.UTF_8) }, stdinTerminal = stdinTerminal()).run(args.toList()))
}

/**
 * Whether stdin is a terminal, where with no input given the REPL would run. On JDK 21, which the CLI compiles against,
 * the console is there only where stdin and stdout both are terminals. From JDK 22, as the native image's GraalVM 25 is,
 * it's there where they aren't too, and `Console.isTerminal` says: called by reflection, as JDK 21 has no such method,
 * which the native image's metadata registers (`java.io.Console`). A pipe or a file on stdin is never a terminal here.
 */
private fun stdinTerminal(): Boolean = System.console()?.let { console ->
    try {
        java.io.Console::class.java.getMethod("isTerminal").invoke(console) as Boolean
    } catch (e: NoSuchMethodException) {
        // JDK 21: the console is there only where both are terminals.
        true
    }
} ?: false

/**
 * One run of the CLI, given its environment, its streams and its working directory rather than the process's, so a test
 * runs it in process as the binary runs. Its output streams are UTF-8, and each line ends in `\n`. [stdin] is opened only
 * where the statements are stdin's, and [stdinTerminal] says whether it's a terminal, where the REPL would run.
 */
class Cli(
    private val env: Map<String, String>,
    private val out: Writer,
    private val err: Writer,
    private val stdin: () -> Reader = { StringReader("") },
    private val stdinTerminal: Boolean = false,
    private val dir: File = File("."),
) {

    /** Runs [args] and gives the exit code: see [ExitCode]. */
    fun run(args: List<String>): Int {
        val code = try {
            when (val parsed = Args.parse(args)) {
                Parsed.Help -> exit(print { it.write(Args.USAGE) })
                Parsed.Version -> exit(print { it.write("drivine $VERSION\n") })
                is Parsed.Run -> run(parsed.invocation.check(env, dir, stdinTerminal))
            }
        } catch (e: BadInvocation) {
            fail(ExitCode.BAD_INVOCATION, e.message.orEmpty())
        }
        try {
            err.flush()
        } catch (e: IOException) {
            // stderr closed by its reader: there's nothing left to tell it.
        }
        return code
    }

    /**
     * Runs [plan]'s statements in order, each in its own transaction, on one connection to its store, and prints each
     * result as it comes. A statement that fails prints its message on stderr, and the run stops there with
     * [ExitCode.STATEMENT_FAILED], or goes on with `--continue` and ends with it. A store that can't be reached ends the run
     * with [ExitCode.UNREACHABLE] at once, as nothing after could run.
     */
    private fun run(plan: Plan): Int {
        val store = try {
            Store.open(plan)
        } catch (e: Unreachable) {
            return unreachable(plan, e)
<<<<<<< HEAD
        }
        return store.use {
            try {
                reader(plan.source).use { reader ->
                    Session(store, Renderer.of(plan.format), output, dir, plan.continueOnFailure) { withoutMark(it.bufferedReader(Charsets.UTF_8)) }
                        .run(plan.parameters, Statements(reader))
                }
            } catch (e: IOException) {
                // The input couldn't be read to its end, as a file removed under the run or stdin's pipe broken.
                trace(e)
                fail(ExitCode.STATEMENT_FAILED, "drivine: can't read the statements: ${e.message ?: e.javaClass.name}")
            }
        }
    }

    /** [code] as the run's exit code: [DONE], stdout closed by its reader, is [ExitCode.OK]. */
    private fun exit(code: Int): Int = if (code == DONE) ExitCode.OK else code

    /** The run's stdout and stderr, as its [Session] writes to them. */
    private val output = object : Output {
        override fun print(write: (Appendable) -> Unit): Int = this@Cli.print { write(it) }
        override fun fail(code: Int, message: String): Int = this@Cli.fail(code, message)
        override fun warn(message: String) {
            this@Cli.fail(ExitCode.OK, message)
        }
        override fun trace(e: Throwable) = this@Cli.trace(e)
    }
=======
        }
        return store.use {
            try {
                reader(plan.source).use { reader -> runAll(plan, store, Statements(reader)) }
            } catch (e: IOException) {
                // The input couldn't be read to its end, as a file removed under the run or stdin's pipe broken.
                trace(e)
                fail(ExitCode.STATEMENT_FAILED, "drivine: can't read the statements: ${e.message ?: e.javaClass.name}")
            }
        }
    }

    private fun runAll(plan: Plan, store: Store, inputs: Iterator<Input>): Int {
        val renderer = Renderer.of(plan.format)
        var failed = false
        for (input in inputs) {
            val code = when (input) {
                is Input.Statement -> statement(plan, store, renderer, input.cypher)
                is Input.Command -> when (val name = input.name) {
                    ":exit", ":quit" -> break
                    in LATER_COMMANDS -> fail(ExitCode.STATEMENT_FAILED, "drivine: $name comes later, and this build doesn't run it")
                    else -> fail(ExitCode.STATEMENT_FAILED, "Could not find command $name, use :help to see available commands")
                }
            }
            when (code) {
                ExitCode.OK -> {}
                ExitCode.STATEMENT_FAILED -> {
                    failed = true
                    if (!plan.continueOnFailure) return code
                }
                Done -> break
                else -> return code
            }
        }
        return if (failed) ExitCode.STATEMENT_FAILED else ExitCode.OK
    }

    /** Runs one statement and prints what it gives: [ExitCode.OK], or the code it failed with, or [Done] where stdout was closed. */
    private fun statement(plan: Plan, store: Store, renderer: Renderer, cypher: String): Int {
        val result = try {
            store.run(cypher)
        } catch (e: Unreachable) {
            return unreachable(plan, e)
        } catch (e: Exception) {
            trace(e)
            // The store's message, or the compiler's refusal: a statement that failed prints nothing to stdout.
            return fail(ExitCode.STATEMENT_FAILED, e.message ?: e.javaClass.name)
        }
        return print { renderer.render(result, it) }
    }

    /** [code] as the run's exit code: [Done], stdout closed by its reader, is [ExitCode.OK]. */
    private fun exit(code: Int): Int = if (code == Done) ExitCode.OK else code
>>>>>>> origin/main

    private fun unreachable(plan: Plan, e: Unreachable): Int {
        trace(e)
        return fail(ExitCode.UNREACHABLE, "drivine: can't connect to the ${plan.address.store.flag} store: ${e.message}")
    }

<<<<<<< HEAD
    /**
     * The statements' text, read as it comes: the command line's, the file's in UTF-8, or stdin's. A file's or stdin's
     * one byte-order mark at its very start isn't part of its text, as the contract's "Files" says: an editor on Windows
     * may write one, and a command on the first line is still a command.
     */
    private fun reader(source: Source): Reader = when (source) {
        is Source.Text -> StringReader(source.cypher)
        is Source.File -> withoutMark(source.file.bufferedReader(Charsets.UTF_8))
        Source.Stdin -> withoutMark(BufferedReader(stdin()))
=======
    /** The statements' text, read as it comes: the command line's, the file's in UTF-8, or stdin's. */
    private fun reader(source: Source): Reader = when (source) {
        is Source.Text -> StringReader(source.cypher)
        is Source.File -> source.file.bufferedReader(Charsets.UTF_8)
        Source.Stdin -> BufferedReader(stdin())
>>>>>>> origin/main
    }

    /**
     * Writes to stdout with [write] and flushes it. stdout closed by its reader, as `drivine … | head` closes it, ends
     * the run with 0: the rows nobody reads aren't printed, with no stack trace. Any other failure to write, as a full
     * disk under `> file` gives, cuts the output short, so it fails the run with 1.
     */
    private fun print(write: (Writer) -> Unit): Int =
        try {
            write(out)
            out.flush()
            ExitCode.OK
        } catch (e: IOException) {
<<<<<<< HEAD
            if (closedPipe(e)) DONE
=======
            if (closedPipe(e)) Done
>>>>>>> origin/main
            else fail(ExitCode.STATEMENT_FAILED, "drivine: can't write the result: ${e.message ?: e.javaClass.name}")
        }

    /**
     * [e]'s stack trace, its causes' too, on stderr, where `DRIVINE_TRACE_ERRORS=true`: to see why a store or a native
     * build fails, where the message alone doesn't say. It isn't part of the contract, and `--log` takes its place.
     */
    private fun trace(e: Throwable) {
        if (env["DRIVINE_TRACE_ERRORS"] == "true") err.write(e.stackTraceToString())
    }

    /**
     * Prints [message] on stderr and gives [code]. stderr is flushed at once, so that under `--continue` each failure is
     * reported as it comes, before the results after it, and not when the run ends.
     */
    private fun fail(code: Int, message: String): Int {
        try {
            err.write(message.trimEnd())
            err.write("\n")
            err.flush()
        } catch (e: IOException) {
            // stderr closed by its reader: there's nothing left to tell it, and the code still says how the run went.
        }
        return code
    }

    companion object {
        /**
         * Not an exit code: stdout's reader closed it, so no more is to be run or printed, and the run ends with
         * [ExitCode.OK], or with [ExitCode.STATEMENT_FAILED] where a statement failed before it under `--continue`. Only
<<<<<<< HEAD
         * [print] and the [Session] see it.
         */
        internal const val DONE = -1
=======
         * [print] and the statement loop see it.
         */
        private const val Done = -1

        /** The contract's commands, but `:exit` and `:quit`, which this build doesn't run yet: each fails as a statement does. */
        internal val LATER_COMMANDS = setOf(":begin", ":commit", ":rollback", ":param", ":params", ":use", ":source", ":help")
>>>>>>> origin/main

        /**
         * Whether [e] is a write to a pipe its reader closed. The JDK's FileOutputStream, and so the native image, which
         * links the same code, gives EPIPE's text, "Broken pipe" on Linux and macOS alike, and "Stream closed" (or
         * "Stream Closed") for a stream already closed.
         */
        internal fun closedPipe(e: IOException): Boolean =
            e.message.orEmpty().let { it.equals("Broken pipe", ignoreCase = true) || it.equals("Stream closed", ignoreCase = true) }

        /** [reader] past one byte-order mark (U+FEFF) at its very start, where it has one; any other is its text's. */
        internal fun withoutMark(reader: Reader): Reader {
            val pushback = PushbackReader(reader, 1)
            val first = pushback.read()
            if (first != -1 && first != BYTE_ORDER_MARK) pushback.unread(first)
            return pushback
        }

        private const val BYTE_ORDER_MARK = 0xFEFF

        /** This build's version, which the build writes into the CLI's resources. */
        val VERSION: String by lazy {
            Cli::class.java.getResourceAsStream("drivine-cli.properties")?.use { stream ->
                Properties().apply { load(stream) }.getProperty("version")
            } ?: "unknown"
        }
    }
}
