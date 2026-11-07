package org.drivine.schema.ddl

import org.drivine.schema.SchemaItemSpec
import org.drivine.schema.UniquenessConstraintSpec

/**
 * What [SchemaDdl.translate] makes of a statement: Neo4j's schema DDL as Drivine's schema specs, a refusal, or
 * not DDL at all.
 */
sealed interface SchemaDdlResult {

    /** The statement is not schema DDL: Cypher, or anything else, to be run or compiled as it is. */
    data object NotDdl : SchemaDdlResult

    /**
     * `CREATE … INDEX` or `CREATE CONSTRAINT`, as [spec]. The spec is unnamed (its name is null), so the name
     * the schema API gives it, [SchemaItemSpec.defaultName], is the name it is made under. A name the DDL gave
     * that differs from that one is [alias], kept beside the item so that a `DROP` of it finds the item.
     */
    data class Create(val spec: SchemaItemSpec, val alias: String?, val ifNotExists: Boolean) : SchemaDdlResult

    /** `DROP INDEX` or `DROP CONSTRAINT` of the item [name]s: by the schema API's name, or by an alias. */
    data class Drop(val kind: Kind, val name: String, val ifExists: Boolean) : SchemaDdlResult {
        enum class Kind { INDEX, CONSTRAINT }
    }

    /** `CALL db.awaitIndexes(…)` or `CALL db.awaitIndex(…)`: an index on a SQL store is made whole as it is created, so nothing is waited for. */
    data object AwaitIndexes : SchemaDdlResult

    /**
     * A uniqueness constraint on one of Drivine's own nodes, the schema marker's `scope` or the store stamp's
     * `key`, by any name: ensured as Drivine ensures it for itself, and recorded in no owner's inventory.
     */
    data class Reserved(val spec: UniquenessConstraintSpec) : SchemaDdlResult

    /** Schema DDL that a SQL store cannot honour, or that is not well formed, and why. */
    data class Refused(val message: String) : SchemaDdlResult
}
