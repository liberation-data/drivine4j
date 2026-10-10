package org.drivine.sample.fragment

import org.drivine.annotation.GraphProperty
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId

/**
 * Exercises stored names that are not plain text to a code generator, through the KSP codegen: a
 * `$`, which a Kotlin string literal reads as a template; a `%`, which the generator's own formatter
 * reads as a directive; a quote and a backslash, which end or escape a literal. The generated
 * `OddlyStoredProperties` must carry each name as it is declared here.
 */
@NodeFragment(labels = ["OddlyStored"])
data class OddlyStored(
    @NodeId val id: String,
    @GraphProperty("price\$usd") val price: Long? = null,
    @GraphProperty("rate%") val rate: Long? = null,
    @GraphProperty("say \"hi\"") val greeting: String? = null,
    @GraphProperty("back\\slash") val path: String? = null,
)
