package org.drivine.cli

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.Properties
import kotlin.system.exitProcess

/**
 * The `drivine` command: one Cypher statement against one store, printed, and an exit code that says how it went.
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
    exitProcess(Cli(System.getenv(), out, err).run(args.toList()))
}

/**
 * One run of the CLI, given its environment and its two streams rather than the process's, so a test runs it in
 * process as the binary runs. Both streams are UTF-8, and each line ends in `\n`.
 */
class Cli(private val env: Map<String, String>, private val out: Writer, private val err: Writer) {

    /** Runs [args] and gives the exit code: see [ExitCode]. */
    fun run(args: List<String>): Int {
        val code = try {
            when (val parsed = Args.parse(args)) {
                Parsed.Help -> print { it.write(Args.USAGE) }
                Parsed.Version -> print { it.write("drivine $VERSION\n") }
                is Parsed.Run -> run(parsed.invocation.check(env))
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

    private fun run(plan: Plan): Int {
        val result = try {
            Store.open(plan).use { store -> store.run(plan.cypher) }
        } catch (e: Unreachable) {
            trace(e)
            return fail(ExitCode.UNREACHABLE, "drivine: can't connect to the ${plan.address.store.flag} store: ${e.message}")
        } catch (e: Exception) {
            trace(e)
            // The store's message, or the compiler's refusal: a statement that failed prints nothing to stdout.
            return fail(ExitCode.STATEMENT_FAILED, e.message ?: e.javaClass.name)
        }
        return print { Renderer.of(plan.format).render(result, it) }
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
            if (closedPipe(e)) ExitCode.OK
            else fail(ExitCode.STATEMENT_FAILED, "drivine: can't write the result: ${e.message ?: e.javaClass.name}")
        }

    /**
     * [e]'s stack trace, its causes' too, on stderr, where `DRIVINE_TRACE_ERRORS=true`: to see why a store or a native
     * build fails, where the message alone doesn't say. It isn't part of the contract, and `--log` takes its place.
     */
    private fun trace(e: Throwable) {
        if (env["DRIVINE_TRACE_ERRORS"] == "true") err.write(e.stackTraceToString())
    }

    private fun fail(code: Int, message: String): Int {
        err.write(message.trimEnd())
        err.write("\n")
        return code
    }

    companion object {
        /**
         * Whether [e] is a write to a pipe its reader closed. The JDK's FileOutputStream, and so the native image, which
         * links the same code, gives EPIPE's text, "Broken pipe" on Linux and macOS alike, and "Stream closed" (or
         * "Stream Closed") for a stream already closed.
         */
        internal fun closedPipe(e: IOException): Boolean =
            e.message.orEmpty().let { it.equals("Broken pipe", ignoreCase = true) || it.equals("Stream closed", ignoreCase = true) }

        /** This build's version, which the build writes into the CLI's resources. */
        val VERSION: String by lazy {
            Cli::class.java.getResourceAsStream("drivine-cli.properties")?.use { stream ->
                Properties().apply { load(stream) }.getProperty("version")
            } ?: "unknown"
        }
    }
}
