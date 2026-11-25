package org.drivine.query

/**
 * A statement's text and the language it is in. One that names no language ([QueryLanguage.PLATFORM_DEFAULT]) is in its
 * connection's default language: Cypher on every store, unless the datasource is registered to default to SQL.
 */
open class Statement(val text: String, val language: QueryLanguage = QueryLanguage.PLATFORM_DEFAULT)

/** A statement in Cypher, whatever the connection's default: compiled to the store's SQL on Postgres, Oracle and SQLite. */
class CypherStatement(text: String) : Statement(text, QueryLanguage.CYPHER)

/** A statement in the store's SQL, run as it is, whatever the connection's default. */
class SqlStatement(text: String) : Statement(text, QueryLanguage.SQL)

fun cypherStatement(text: String): CypherStatement {
    return CypherStatement(text)
}

fun sqlStatement(text: String): SqlStatement {
    return SqlStatement(text)
}

fun toPlatformDefault(language: QueryLanguage, statement: Statement): Statement {
    return if (statement.language == QueryLanguage.PLATFORM_DEFAULT) {
        Statement(statement.text, language) // Change language to the specified one
    } else {
        statement // Return the statement unchanged if the language is not PLATFORM_DEFAULT
    }
}
