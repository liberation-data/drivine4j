package org.drivine.query

/** The language a [Statement] is written in. */
enum class QueryLanguage {
    /**
     * None named: the connection's default language. That is Cypher on every store, compiled to the store's SQL on
     * Postgres, Oracle and SQLite, unless the datasource is registered with `defaultLanguage = SQL`
     * (`org.drivine.connection.ConnectionProperties.defaultLanguage`).
     */
    PLATFORM_DEFAULT,
    CYPHER,
    SQL
}
