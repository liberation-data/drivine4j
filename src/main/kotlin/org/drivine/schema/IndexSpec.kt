package org.drivine.schema

/**
 * A declarative description of an index.
 *
 * @see VectorIndexSpec
 * @see RangeIndexSpec
 * @see FullTextIndexSpec
 */
sealed interface IndexSpec : SchemaItemSpec