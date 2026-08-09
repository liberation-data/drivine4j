package org.drivine.sample.fragment

import org.drivine.annotation.GraphProperty
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.PropertyBag

/**
 * Exercises **model-aware key resolution** through the KSP codegen: a promoted `@GraphProperty` field
 * (`sectionId` → on-disk `section_id`) alongside a free-form `@PropertyBag(prefix = "metadata")`. The
 * generated `SampleResolverNodeQueryDsl` must implement `ResolvableNodeReference` and emit a
 * `fieldKeyPaths` mapping both `sectionId` and `section_id` → `section_id`, plus `bagPrefixes = ["metadata."]`.
 */
@NodeFragment(labels = ["SampleResolver"])
data class SampleResolverNode(
    @NodeId val id: String,
    @GraphProperty("section_id") val sectionId: String? = null,
    @PropertyBag(prefix = "metadata") val metadata: Map<String, Any?> = emptyMap(),
)
