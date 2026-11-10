package org.drivine.cli

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
    /** Each statement given, by `-c` or as the argument. More than one is two inputs, which [check] refuses. */
    val inputs: List<String> = emptyList(),
    val user: String? = null,
    val type: StoreType? = null,
    val format: Format = Format.AUTO,
) {
    /**
     * This invocation, checked in the contract's order once the flags have been read: the inputs, then the address, then
     * the user and the password, which come from the flags, then [env]. Throws [BadInvocation] for the first that is wrong.
     */
    fun check(env: Map<String, String>): Plan {
        if (inputs.size > 1) throw BadInvocation("drivine: give one statement, as the argument or with -c, not both or two; see --help")
        val cypher = inputs.singleOrNull()
            ?: throw BadInvocation("drivine: give a statement, as the argument or with -c; files, stdin and the REPL come later. See --help")
        val (text, fromCommandLine) = address?.let { it to true }
            ?: env[ENV_ADDRESS]?.let { it to false }
            ?: throw BadInvocation("drivine: no address: give -a ADDRESS or $ENV_ADDRESS; see --help")
        val parsed = Address.parse(text, type, fromCommandLine)
        // The user: -u, then the address's, then DRIVINE_USER. The password: DRIVINE_PASSWORD, then the address's, which
        // only an address kept off the command line may hold.
        return Plan(
            address = parsed,
            cypher = cypher,
            format = format,
            user = user ?: parsed.user ?: env[ENV_USER],
            password = env[ENV_PASSWORD] ?: parsed.password,
        )
    }

    companion object {
        const val ENV_ADDRESS = "DRIVINE_ADDRESS"
        const val ENV_USER = "DRIVINE_USER"
        const val ENV_PASSWORD = "DRIVINE_PASSWORD"
    }
}

/** A checked invocation: one statement, the store it runs on, and how to print what it gives. */
data class Plan(
    val address: Address,
    val cypher: String,
    val format: Format,
    val user: String?,
    val password: String?,
) {
    override fun toString(): String = "Plan(address=$address, cypher=$cypher, format=$format, user=$user, password=${password?.let { "***" }})"
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

    /** The contract's password refusal, word for word: a password on the command line shows in `ps` and in shell history. */
    const val PASSWORD_REFUSED =
        "drivine: a password on the command line shows in ps and in your shell history; use DRIVINE_PASSWORD, a profile or the prompt."

    /** The contract's flags that this build doesn't take yet: still a bad invocation, but said so, not called unknown. */
    internal val LATER = setOf(
        "-f", "--file", "-d", "--database", "-g", "--graph", "--profile", "-P", "--param", "--sample-rows", "--wrap",
        "--continue", "--explain", "--non-interactive", "--history", "--idle-timeout", "--transaction-timeout", "--log",
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
            // A long flag may give its value after a `=`, as in --format=json.
            val (flag, inline) = if (arg.startsWith("--") && '=' in arg) arg.substringBefore('=') to arg.substringAfter('=') else arg to null
            fun value(): String = inline ?: args.getOrNull(i++) ?: throw BadInvocation("drivine: $flag needs a value; see --help")
            fun noValue() {
                if (inline != null) throw BadInvocation("drivine: $flag takes no value; see --help")
            }
            // -pSECRET is -p with its value attached, as getopt and cypher-shell read it: refused as -p, not named back.
            if (flag.startsWith("-p")) throw BadInvocation(PASSWORD_REFUSED)
            when (flag) {
                "-h", "--help" -> { noValue(); return Parsed.Help }
                "-v", "--version" -> { noValue(); return Parsed.Version }
                "--password" -> throw BadInvocation(PASSWORD_REFUSED)
                "-a", "--address" -> invocation = invocation.copy(address = value())
                "-c" -> invocation = invocation.copy(inputs = invocation.inputs + value())
                "-u", "--user", "--username" -> invocation = invocation.copy(user = value())
                "--type" -> {
                    val named = value()
                    val type = StoreType.named(named)
                        ?: throw BadInvocation("drivine: --type $named isn't a store: give one of ${StoreType.entries.joinToString { it.flag }}")
                    invocation = invocation.copy(type = type)
                }
                "--format" -> {
                    val named = value()
                    if (named in Format.LATER) throw BadInvocation("drivine: --format $named comes later; give auto, plain or json")
                    val format = Format.entries.find { it.flag == named }
                        ?: throw BadInvocation("drivine: --format $named isn't a format: give auto, plain or json")
                    invocation = invocation.copy(format = format)
                }
                in LATER -> throw BadInvocation("drivine: $flag comes later, and this build doesn't take it; see --help")
                else -> throw BadInvocation("drivine: unknown flag $flag; see --help")
            }
        }
        return Parsed.Run(invocation)
    }

    /** What `--help` prints: the flags this build takes. */
    val USAGE: String = """
        |Usage: drivine [flags] [cypher]
        |
        |Runs one Cypher statement against a store and exits. On Postgres, Oracle and SQLite, Drivine compiles it to SQL.
        |
        |  cypher, -c CYPHER            The statement to run.
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
