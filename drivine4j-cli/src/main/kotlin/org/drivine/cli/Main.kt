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
        try {
            return when (val parsed = Args.parse(args)) {
                Parsed.Help -> ok(Args.USAGE)
                Parsed.Version -> ok("drivine $VERSION\n")
                is Parsed.Run -> run(parsed.invocation.check(env))
            }
        } catch (e: BadInvocation) {
            return fail(ExitCode.BAD_INVOCATION, e.message.orEmpty())
        } finally {
            flush(out)
            flush(err)
        }
    }

    private fun run(plan: Plan): Int {
        val result = try {
            Store.open(plan).use { store -> store.run(plan.cypher) }
        } catch (e: Unreachable) {
            return fail(ExitCode.UNREACHABLE, "drivine: can't connect to the ${plan.address.store.flag} store: ${e.message}")
        } catch (e: Exception) {
            // The store's message, or the compiler's refusal: a statement that failed prints nothing to stdout.
            return fail(ExitCode.STATEMENT_FAILED, e.message ?: e.javaClass.name)
        }
        try {
            Renderer.of(plan.format).render(result, out)
            out.flush()
        } catch (e: IOException) {
            // stdout closed by its reader, as `drivine … | head` closes it: the statement ran, and the rows nobody reads
            // aren't printed, with no stack trace.
        }
        return ExitCode.OK
    }

    /** Flushes [stream], where its reader hasn't closed it: see [run]. */
    private fun flush(stream: Writer) {
        try {
            stream.flush()
        } catch (e: IOException) {
            // Closed by its reader.
        }
    }

    private fun ok(text: String): Int {
        out.write(text)
        return ExitCode.OK
    }

    private fun fail(code: Int, message: String): Int {
        err.write(message.trimEnd())
        err.write("\n")
        return code
    }

    companion object {
        /** This build's version, which the build writes into the CLI's resources. */
        val VERSION: String by lazy {
            Cli::class.java.getResourceAsStream("drivine-cli.properties")?.use { stream ->
                Properties().apply { load(stream) }.getProperty("version")
            } ?: "unknown"
        }
    }
}
