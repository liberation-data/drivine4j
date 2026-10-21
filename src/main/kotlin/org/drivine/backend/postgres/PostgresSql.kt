package org.drivine.backend.postgres

import org.drivine.model.Stamps

/** The pieces of SQL every statement over a [PostgresGraph] is made of. */
internal object PostgresSql {

    /** [text] as a SQL string literal, its quotes doubled. Postgres reads a backslash in one as itself. */
    fun literal(text: String): String = "'${text.replace("'", "''")}'"

    /** The property [key] of the node or relationship [alias], read as text. The stamp is a column. */
    fun property(alias: String, key: String): String {
        require(key.isNotEmpty()) { "A property name is empty: an empty name is no property a statement can read." }
        return if (key == Stamps.PROPERTY) "$alias.stamp" else "$alias.props ->> ${literal(key)}"
    }
}
