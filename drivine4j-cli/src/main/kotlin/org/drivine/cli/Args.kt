package org.drivine.cli

import java.io.File

/**
 * The exit codes of the CLI's contract (drivine-cypher-spec's cli/README.md, "Failures and exit codes"). A script tells
 * a statement that failed from a bad invocation and from a store it couldn't reach, which cypher-shell's single 1 doesn't let it do.
 */
object ExitCode {
    const val OK = 0

    /** A statement failed. */
    const val STATEMENT_FAILED = 1

    /** The invocation is wrong. Nothing ran, and the CLI didn't connect. */
    const val BAD_INVOCATION = 2

    /** The store can't be reached, or it refused the user and password. Nothing ran. */
    const val UNREACHABLE = 3
}

/** An invocation the CLI refuses, with the one line it prints to stderr: exit code [ExitCode.BAD_INVOCATION]. */
class BadInvocation(message: String) : Exception(message)

/**
 * The output formats. The contract has five: `table` and `csv` come later, so [Args] refuses them for now, and [AUTO],
 * which is `table` on a terminal, is [PLAIN] until `table` is here.
 */
enum class Format(val flag: String) {
    AUTO("auto"),
    PLAIN("plain"),
    JSON("json"),
    ;

    companion object {
        /** The contract's formats that this build doesn't print yet. */
        val LATER = setOf("table", "csv")
    }
}

/**
 * The stores, by the names `--type` takes. A store is named by its address's scheme, but for a `bolt://` address,
 * which Neo4j and Memgraph both speak.
 */
enum class StoreType(val flag: String) {
    NEO4J("neo4j"),
    MEMGRAPH("memgraph"),
    FALKORDB("falkordb"),
    POSTGRES("postgres"),
    ORACLE("oracle"),
    SQLITE("sqlite"),
    ;

    companion object {
        fun named(flag: String): StoreType? = entries.find { it.flag == flag }
    }
}

/** What the flags asked for, read before anything else is checked: see [Args.parse]. */
sealed interface Parsed {
    data object Help : Parsed
    data object Version : Parsed

    /** The flags of a run, which [Invocation.check] checks next. */
    data class Run(val invocation: Invocation) : Parsed
}

/** The flags of a run, as given. Nothing is checked against another flag or the environment yet: see [check]. */
data class Invocation(
    val address: String? = null,
    /** Each text given, by `-c` or as the argument. More than one is two inputs, which [check] refuses. */
    val inputs: List<String> = emptyList(),
    /** Each file `-f` names. More than one, or one with a text, is two inputs. */
    val files: List<String> = emptyList(),
    val user: String? = null,
    val type: StoreType? = null,
    val format: Format = Format.AUTO,
    /** `--continue`: run every statement, and report each failure as it comes, rather than stop at the first. */
    val continueOnFailure: Boolean = false,
    /** `--non-interactive`: read stdin, even where it's a terminal. */
    val nonInteractive: Boolean = false,
    /** Each `-P`, in the order given: a later one of the same name replaces an earlier. */
    val parameters: List<Assignment> = emptyList(),
) {
    /**
     * This invocation, checked in the contract's order once the flags have been read: the inputs, then the address, then
     * the user and the password, which come from the flags, then [env]. Throws [BadInvocation] for the first that is wrong.
     * A `-f` file is looked for in [dir], and must be one the CLI can read, as nothing runs where it can't. With no input
     * given, the statements are stdin's, unless stdin is a terminal ([stdinTerminal]) and `--non-interactive` isn't given:
     * that is the REPL's, which comes later.
     */
    fun check(env: Map<String, String>, dir: File = File("."), stdinTerminal: Boolean = false): Plan {
        if (inputs.size + files.size > 1) {
            throw BadInvocation("drivine: give one input, a statement as the argument or with -c, or -f FILE, not two; see --help")
        }
        val source = when {
            inputs.isNotEmpty() -> Source.Text(inputs.single())
            files.isNotEmpty() -> Source.File(readable(dir, files.single()))
            stdinTerminal && !nonInteractive ->
                throw BadInvocation("drivine: give a statement, -f FILE, or statements on stdin; the REPL comes later. See --help")
            else -> Source.Stdin
        }
        val (text, fromCommandLine) = address?.let { it to true }
            ?: env[ENV_ADDRESS]?.let { it to false }
            ?: throw BadInvocation("drivine: no address: give -a ADDRESS or $ENV_ADDRESS; see --help")
        val parsed = Address.parse(text, type, fromCommandLine)
        // The user: -u, then the address's, then DRIVINE_USER. The password: DRIVINE_PASSWORD, then the address's, which
        // only an address kept off the command line may hold.
        return Plan(
            address = parsed,
            source = source,
            format = format,
            continueOnFailure = continueOnFailure,
            parameters = parameters,
            user = user ?: parsed.user ?: env[ENV_USER],
            password = env[ENV_PASSWORD] ?: parsed.password,
        )
    }

    companion object {
        /**
         * [path], from [dir] where it's relative, as a file the CLI can read: a bad invocation where it isn't, which names
         * the path as given, as a `-f` value holds no secret.
         */
        private fun readable(dir: File, path: String): File {
            val file = File(path).let { if (it.isAbsolute) it else File(dir, path) }
            if (!file.isFile || !file.canRead()) throw BadInvocation("drivine: can't read -f $path: no such file, or not one this user can read")
            return file
        }

        const val ENV_ADDRESS = "DRIVINE_ADDRESS"
        const val ENV_USER = "DRIVINE_USER"
        const val ENV_PASSWORD = "DRIVINE_PASSWORD"
    }
}

/** Where a run's statements come from: a text given on the command line, a `-f` file, or stdin. */
sealed interface Source {
    data class Text(val cypher: String) : Source
    data class File(val file: java.io.File) : Source
    data object Stdin : Source
}

/** A checked invocation: its statements, the store they run on, and how to print what they give. */
data class Plan(
    val address: Address,
    val source: Source,
    val format: Format,
    val user: String?,
    val password: String?,
    val continueOnFailure: Boolean = false,
    /** The `-P` parameters, evaluated on the store before any statement runs. */
    val parameters: List<Assignment> = emptyList(),
) {
    override fun toString(): String =
        "Plan(address=$address, source=$source, format=$format, user=$user, password=${password?.let { "***" }}, " +
            "continueOnFailure=$continueOnFailure, parameters=$parameters)"
}

/**
 * Reads the command line by hand: the CLI is to be one native binary that starts in milliseconds, so it takes no
 * argument library, and its flags are few.
 *
 * The flags are read in the order given, and the first that ends the run ends it with nothing else checked, as the
 * contract says: an unknown or refused flag, or a flag's missing or bad value, is a [BadInvocation], and `-h` or `-v`
 * ends it with 0. So `drivine --nope` names `--nope`, not the missing address.
 */
object Args {

    /**
     * The flag [flag] as a refusal names it: a short flag's two characters, and a long flag's name up to anything that
     * isn't a letter, a digit or a `-`. What follows may be a value attached to it, `-aADDRESS` or a mistyped
     * `--addresspostgresql://u:secret@h`, which may hold a password, and isn't printed back.
     */
    internal fun named(flag: String): String =
        if (!flag.startsWith("--")) flag.take(2) else "--" + flag.drop(2).takeWhile { it.isLetterOrDigit() || it == '-' }

    /**
     * A flag's value, [value], as a refusal names it, where it reads as a word, as `xml` does: any other is "that value",
     * as it may be an address or a password given in the wrong place.
     */
    internal fun shownValue(value: String): String = if (Regex("[A-Za-z0-9_-]{1,32}").matches(value)) value else "that value"

    /** The contract's password refusal, word for word: a password on the command line shows in `ps` and in shell history. */
    const val PASSWORD_REFUSED =
        "drivine: a password on the command line shows in ps and in your shell history; use DRIVINE_PASSWORD, a profile or the prompt."

    /** The contract's flags that this build doesn't take yet: still a bad invocation, but said so, not called unknown. */
    internal val LATER = setOf(
        "-d", "--database", "-g", "--graph", "--profile", "--sample-rows", "--wrap",
        "--explain", "--history", "--idle-timeout", "--transaction-timeout", "--log",
        "--driver-version",
    )

    fun parse(args: List<String>): Parsed {
        var invocation = Invocation()
        var i = 0
        while (i < args.size) {
            val arg = args[i++]
            if (!arg.startsWith("-") || arg == "-") {
                invocation = invocation.copy(inputs = invocation.inputs + arg)
                continue
            }
            // A long flag may give its value after a `=`, as in --format=json. A short flag is its two characters, and what
            // is attached to it, as in -vx or -fFILE, its value: so -vx is -v that takes no value, and -fFILE is -f.
            val (flag, inline) = when {
                arg.startsWith("--") -> if ('=' in arg) arg.substringBefore('=') to arg.substringAfter('=') else arg to null
                arg.length > 2 -> arg.take(2) to arg.drop(2)
                else -> arg to null
            }
            fun value(): String = inline ?: args.getOrNull(i++) ?: throw BadInvocation("drivine: ${named(flag)} needs a value; see --help")
            fun noValue() {
                if (inline != null) throw BadInvocation("drivine: ${named(flag)} takes no value; see --help")
            }
            // -pSECRET and --passwordSECRET are a password with its flag, the value attached: refused as -p, not named back.
            if (flag.startsWith("-p") || flag.startsWith("--password")) throw BadInvocation(PASSWORD_REFUSED)
            // -aVALUE and -a=VALUE aren't read, and aren't named back either: the value may be an address with a password.
            if (inline != null && flag in setOf("-a", "-c", "-u")) {
                throw BadInvocation("drivine: give $flag's value as the next argument, as in $flag VALUE; see --help")
            }
            when (flag) {
                "-h", "--help" -> { noValue(); return Parsed.Help }
                "-v", "--version" -> { noValue(); return Parsed.Version }
                "--password" -> throw BadInvocation(PASSWORD_REFUSED)
                "-a", "--address" -> invocation = invocation.copy(address = value())
                "-c" -> invocation = invocation.copy(inputs = invocation.inputs + value())
                "-f", "--file" -> invocation = invocation.copy(files = invocation.files + value())
                "--continue" -> { noValue(); invocation = invocation.copy(continueOnFailure = true) }
                "--non-interactive" -> { noValue(); invocation = invocation.copy(nonInteractive = true) }
                "-P", "--param" -> {
                    // The value isn't named back: a parameter may hold what a script would rather not print.
                    val assignment = Assignment.parse(value())
                        ?: throw BadInvocation("drivine: $flag takes NAME => EXPRESSION, NAME=EXPRESSION or a map, {NAME: EXPRESSION}; see --help")
                    invocation = invocation.copy(parameters = invocation.parameters + assignment)
                }
                "-u", "--user", "--username" -> invocation = invocation.copy(user = value())
                "--type" -> {
                    val named = value()
                    val type = StoreType.named(named)
                        ?: throw BadInvocation("drivine: --type ${shownValue(named)} isn't a store: give one of ${StoreType.entries.joinToString { it.flag }}")
                    invocation = invocation.copy(type = type)
                }
                "--format" -> {
                    val named = value()
                    if (named in Format.LATER) throw BadInvocation("drivine: --format ${shownValue(named)} comes later; give auto, plain or json")
                    val format = Format.entries.find { it.flag == named }
                        ?: throw BadInvocation("drivine: --format ${shownValue(named)} isn't a format: give auto, plain or json")
                    invocation = invocation.copy(format = format)
                }
                in LATER -> throw BadInvocation("drivine: $flag comes later, and this build doesn't take it; see --help")
                else -> throw BadInvocation("drivine: unknown flag ${named(flag)}; see --help")
            }
        }
        return Parsed.Run(invocation)
    }

    /** What `--help` prints: the flags this build takes. */
    val USAGE: String = """
        |Usage: drivine [flags] [cypher]
        |
        |Runs Cypher statements against a store and exits. On Postgres, Oracle and SQLite, Drivine compiles them to SQL.
        |Statements are split on ;, each runs in its own transaction, unless it falls between :begin and :commit, and
        |each result is printed as it comes. A line that starts with : is a command: :help lists them.
        |
        |  cypher, -c CYPHER            The statements to run.
        |  -f, --file FILE              The file's statements. With no cypher, -c or -f, stdin's.
        |  --non-interactive            Read stdin even where it's a terminal.
        |  --continue                   Run every statement, reporting each failure, rather than stop at the first.
        |  -P, --param PARAM            A parameter, NAME => EXPRESSION, NAME=EXPRESSION or {NAME: EXPRESSION, …}:
        |                               the expression is Cypher, which the store evaluates. Given more than once,
        |                               a later one replaces an earlier one of the same name.
        |  -a, --address ADDRESS        The store, else DRIVINE_ADDRESS:
        |                                 neo4j://host:port, bolt://host:port (and their +s and +ssc forms),
        |                                 memgraph://host:port, falkordb://host:port,
        |                                 postgresql://host:port/database, oracle://host:port/service,
        |                                 sqlite:path or sqlite::memory:
        |  --type TYPE                  The store of a bolt:// address: neo4j (the default) or memgraph.
        |  -u, --user, --username USER  The user, else the address's, else DRIVINE_USER.
        |                               The password is DRIVINE_PASSWORD's, never a flag's.
        |  --format FORMAT              auto (the default), plain or json.
        |  -v, --version                Prints drivine's version.
        |  -h, --help                   Prints this.
        |
        |Exit codes: 0 everything ran, 1 a statement failed, 2 a bad invocation, 3 the store can't be reached or refused the user.
        |""".trimMargin()
}
