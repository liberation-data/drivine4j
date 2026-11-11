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
 * The output formats of the contract's "Output". [AUTO] is [TABLE] where stdout is a terminal, and [PLAIN] where it isn't.
 */
enum class Format(val flag: String) {
    AUTO("auto"),
    TABLE("table"),
    PLAIN("plain"),
    JSON("json"),
    CSV("csv"),
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
    /** `--explain`: print what each statement compiles to on a SQL store, and run none. */
    val explain: Boolean = false,
    /** `-d`: the database, or Oracle's service. */
    val database: String? = null,
    /** `-g`: the graph. */
    val graph: String? = null,
    /** `--profile`: the profile of `.drivine.yml` to connect with. */
    val profile: String? = null,
) {
    /**
     * This invocation, checked in the contract's order once the flags have been read: the inputs, then the profile, then
     * the address, the database and the graph, then the user and the password. Each comes from its flag, then [env], then
     * the profile, as the contract's "Connecting" says. Throws [BadInvocation] for the first that is wrong. A `-f` file is
     * looked for in [dir], and must be one the CLI can read, as nothing runs where it can't. With no input given, the
     * statements are stdin's, unless stdin is a terminal ([stdinTerminal]) and `--non-interactive` isn't given: that is
     * the REPL's, which comes later. The profiles are `.drivine.yml` in [home], then in [dir].
     */
    fun check(env: Map<String, String>, dir: File = File("."), stdinTerminal: Boolean = false, home: File? = null): Plan {
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
        val chosen = Profiles.pick(profile, home, dir)
        val (text, fromCommandLine) = address?.let { it to true }
            ?: env[ENV_ADDRESS]?.let { it to false }
            ?: chosen?.address?.let { it to false }
            ?: throw BadInvocation("drivine: no address: give -a ADDRESS, $ENV_ADDRESS or a profile; see --help")
        val storeType = type ?: chosen?.type?.let { named ->
            StoreType.named(named) ?: throw BadInvocation("drivine: the profile ${chosen.name}'s type ${Args.shownValue(named)} isn't a store: give one of ${StoreType.entries.joinToString { it.flag }}")
        }
        val parsed = Address.parse(text, storeType, fromCommandLine)
        if (explain && parsed.store in GRAPH_ENGINES) {
            throw BadInvocation("drivine: --explain prints the SQL a statement compiles to, and ${parsed.store.flag} runs Cypher itself: there's no SQL to print")
        }
        // The database: one the address names wins, then -d, DRIVINE_DATABASE and the profile's. The graph: -g, then
        // DRIVINE_GRAPH and the profile's. Each is refused where the store has none, however it's given.
        val givenDatabase = database ?: env[ENV_DATABASE] ?: chosen?.database
        val givenGraph = graph ?: env[ENV_GRAPH] ?: chosen?.graph
        if (givenDatabase != null && parsed.store !in DATABASES) {
            throw BadInvocation(
                "drivine: ${parsed.store.flag} takes no --database (-d, $ENV_DATABASE or a profile's database): " +
                    if (parsed.store == StoreType.SQLITE) "-a sqlite:path names the file" else "--graph names the graph",
            )
        }
        if (givenGraph != null && parsed.store !in GRAPHS) {
            throw BadInvocation("drivine: ${parsed.store.flag} takes no --graph (-g, $ENV_GRAPH or a profile's graph): --database names the database")
        }
        val database = parsed.database?.takeIf { parsed.store != StoreType.SQLITE } ?: givenDatabase
        if (database == null && parsed.store == StoreType.ORACLE) {
            throw BadInvocation("drivine: give Oracle's service, in the address as oracle://host:port/FREEPDB1, or with -d")
        }
        // The user: -u, then the address's, then DRIVINE_USER, then the profile's. The password: DRIVINE_PASSWORD, then
        // the address's, which only an address kept off the command line may hold, then the profile's, then the prompt.
        return Plan(
            address = parsed,
            source = source,
            format = format,
            continueOnFailure = continueOnFailure,
            parameters = parameters,
            explain = explain,
            user = user ?: parsed.user ?: env[ENV_USER] ?: chosen?.user,
            password = env[ENV_PASSWORD] ?: parsed.password ?: chosen?.password,
            database = database,
            graph = givenGraph,
            profile = chosen?.name,
        )
    }

    companion object {
        /** The stores that run Cypher themselves, where `--explain` has no SQL to print. */
        private val GRAPH_ENGINES = setOf(StoreType.NEO4J, StoreType.MEMGRAPH, StoreType.FALKORDB)

        /** The stores `--database` names a database or a service of: the contract's table, "The database and the graph". */
        private val DATABASES = setOf(StoreType.NEO4J, StoreType.MEMGRAPH, StoreType.POSTGRES, StoreType.ORACLE)

        /** The stores `--graph` names a graph of. */
        private val GRAPHS = setOf(StoreType.FALKORDB, StoreType.POSTGRES, StoreType.ORACLE, StoreType.SQLITE)

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
        const val ENV_DATABASE = "DRIVINE_DATABASE"
        const val ENV_GRAPH = "DRIVINE_GRAPH"
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
    /** `--explain`: each statement's SQL printed, and none run. */
    val explain: Boolean = false,
    /** The database, or Oracle's service: the address's, or else the one given. Null where neither names one. */
    val database: String? = null,
    /** The Drivine graph, or FalkorDB's: null for the store's default. */
    val graph: String? = null,
    /** The profile's name, where one was used. */
    val profile: String? = null,
) {
    override fun toString(): String =
        "Plan(address=$address, source=$source, format=$format, user=$user, password=${password?.let { "***" }}, " +
            "continueOnFailure=$continueOnFailure, parameters=$parameters, explain=$explain, database=$database, graph=$graph, profile=$profile)"
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
        "--sample-rows", "--wrap",
        "--history", "--idle-timeout", "--transaction-timeout", "--log",
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
                "--explain" -> { noValue(); invocation = invocation.copy(explain = true) }
                "-P", "--param" -> {
                    // The value isn't named back: a parameter may hold what a script would rather not print.
                    val assignment = Assignment.parse(value())
                        ?: throw BadInvocation("drivine: $flag takes NAME => EXPRESSION, NAME=EXPRESSION or a map, {NAME: EXPRESSION}; see --help")
                    invocation = invocation.copy(parameters = invocation.parameters + assignment)
                }
                "-u", "--user", "--username" -> invocation = invocation.copy(user = value())
                "-d", "--database" -> invocation = invocation.copy(database = value())
                "-g", "--graph" -> invocation = invocation.copy(graph = value())
                "--profile" -> invocation = invocation.copy(profile = value())
                "--type" -> {
                    val named = value()
                    val type = StoreType.named(named)
                        ?: throw BadInvocation("drivine: --type ${shownValue(named)} isn't a store: give one of ${StoreType.entries.joinToString { it.flag }}")
                    invocation = invocation.copy(type = type)
                }
                "--format" -> {
                    val named = value()
                    val format = Format.entries.find { it.flag == named }
                        ?: throw BadInvocation("drivine: --format ${shownValue(named)} isn't a format: give auto, table, plain, json or csv")
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
        |  --explain                    On Postgres, Oracle or SQLite: print the SQL each statement compiles to, then
        |                               -- parameters: and its parameters as JSON, and run none. -P and :param still run.
        |  -P, --param PARAM            A parameter, NAME => EXPRESSION, NAME=EXPRESSION or {NAME: EXPRESSION, …}:
        |                               the expression is Cypher, which the store evaluates. Given more than once,
        |                               a later one replaces an earlier one of the same name.
        |  -a, --address ADDRESS        The store, else DRIVINE_ADDRESS:
        |                                 neo4j://host:port, bolt://host:port (and their +s and +ssc forms),
        |                                 memgraph://host:port, falkordb://host:port,
        |                                 postgresql://host:port/database, oracle://host:port/service,
        |                                 sqlite:path or sqlite::memory:
        |                               else a profile's address.
        |  --type TYPE                  The store of a bolt:// address: neo4j (the default) or memgraph.
        |  -d, --database DATABASE      The database (Neo4j, Memgraph, Postgres) or the service (Oracle), else
        |                               DRIVINE_DATABASE, else the profile's: one the address names wins.
        |  -g, --graph GRAPH            The graph (FalkorDB, Postgres, Oracle, SQLite), else DRIVINE_GRAPH, else
        |                               the profile's.
        |  -u, --user, --username USER  The user, else the address's, else DRIVINE_USER, else the profile's.
        |                               The password is DRIVINE_PASSWORD's, else the profile's, else asked for
        |                               where stdin is a terminal: never a flag's.
        |  --profile NAME               The profile of ~/.drivine.yml or ./.drivine.yml to connect with, else its
        |                               default:. Flags and DRIVINE_ variables override its keys.
        |  --format FORMAT              auto (the default: table where stdout is a terminal, plain where it isn't),
        |                               table, plain, json or csv. table's --sample-rows and --wrap come later.
        |  -v, --version                Prints drivine's version.
        |  -h, --help                   Prints this.
        |
        |Exit codes: 0 everything ran, 1 a statement or a command failed, 2 a bad invocation, 3 the store can't be reached or refused the user.
        |""".trimMargin()
}
