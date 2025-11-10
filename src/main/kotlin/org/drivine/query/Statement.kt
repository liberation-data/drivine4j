package org.drivine.query

open class Statement(val text: String, val language: QueryLanguage = QueryLanguage.PLATFORM_DEFAULT)

class CypherStatement(text: String) : Statement(text, QueryLanguage.CYPHER)

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
