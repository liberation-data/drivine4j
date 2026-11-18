package org.drivine.autoconfigure

import org.drivine.annotation.NodeFragment
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.DatabaseType
import org.drivine.schema.SchemaCatalog
import org.drivine.schema.TextIndexSpec
import org.drivine.schema.VectorDimensionProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.boot.autoconfigure.AutoConfigurationPackages
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.util.ClassUtils

/**
 * The schema the `@NodeFragment` classes of an application's packages declare, read into a [SchemaCatalog] at startup, so
 * that an application gets the indexes and constraints its fragments' annotations name without listing each fragment in
 * a catalog bean of its own (`drivine.schema.scan-fragments`, on unless set `false`).
 *
 * The packages are `drivine.schema.scan-packages` where set, and otherwise Spring Boot's auto-configuration packages,
 * those of the `@SpringBootApplication` class; where neither is known, as under `@EnableDrivine` without auto-configuration,
 * nothing is scanned. A fragment is a class or an interface annotated `@NodeFragment`.
 *
 * Scanning never fails startup:
 * - a fragment that can't be read into a catalog, as one with a `@VectorIndex` and no single [VectorDimensionProvider] bean, is
 *   passed over, and said so at INFO: register it in a catalog bean of the application's own;
 * - fragments that declare conflicting items are passed over together, and said so at WARN; the rest are applied;
 * - a TEXT index, which only Neo4j makes, is applied to the Neo4j databases alone, so that another store does not
 *   refuse it at startup;
 * - an item an application's own catalog declares, under the same kind, label and properties, is left to that catalog
 *   ([SchemaCatalog.except]), so an application that names or tunes an item keeps its own.
 */
internal object NodeFragmentScan {

    private val logger = LoggerFactory.getLogger(NodeFragmentScan::class.java)

    /** The packages to scan: [configured] where given, else the auto-configuration packages [beanFactory] knows; none where neither is. */
    fun packages(beanFactory: BeanFactory, configured: List<String>): List<String> = configured.ifEmpty {
        if (AutoConfigurationPackages.has(beanFactory)) AutoConfigurationPackages.get(beanFactory) else emptyList()
    }

    /** The `@NodeFragment` classes and interfaces under [packages], each once, in name order. */
    fun fragments(packages: List<String>, classLoader: ClassLoader?): List<Class<*>> {
        if (packages.isEmpty()) return emptyList()
        val scanner = object : ClassPathScanningCandidateComponentProvider(false) {
            // An interface is a fragment too: any type that stands on its own.
            override fun isCandidateComponent(beanDefinition: AnnotatedBeanDefinition) = beanDefinition.metadata.isIndependent
        }
        // Found through the loader the classes are loaded by.
        if (classLoader != null) scanner.resourceLoader = DefaultResourceLoader(classLoader)
        scanner.addIncludeFilter(AnnotationTypeFilter(NodeFragment::class.java))
        return packages.flatMap { pack ->
            // A class file the scanner can't read fails only the package it is in, not startup.
            runCatching { scanner.findCandidateComponents(pack) }
                .onFailure { logger.warn("The package {} could not be scanned for @NodeFragment classes: {}", pack, it.toString()) }
                .getOrDefault(emptySet())
        }
            .mapNotNull { it.beanClassName }
            .distinct()
            .sorted()
            .mapNotNull { name ->
                runCatching { ClassUtils.forName(name, classLoader) }
                    .onFailure { logger.info("@NodeFragment {} could not be loaded to read its schema: {}", name, it.toString()) }
                    .getOrNull()
            }
    }

    /**
     * The Neo4j databases of [registry] that manage schema, which a scanned TEXT index is given to: one that doesn't, as a
     * provider an application registers with no Neo4j dialect, or one with a schema refusal, would fail startup, a
     * catalog naming it being strict ([org.drivine.schema.SchemaTargets]).
     */
    fun neo4jDatabases(registry: DatabaseRegistry): Set<String> = registry.databaseNames.filterTo(mutableSetOf()) { name ->
        registry.connectionProvider(name)?.let { it.type == DatabaseType.NEO4J && it.supportsSchemaManagement } == true
    }

    /**
     * The catalogs [fragments] declare, without what [declared], the application's own catalogs, declare; none where
     * there is nothing. A fragment's vector index takes its size from [dimensions]. A TEXT index, which only Neo4j makes
     * and every other store refuses, is given in a catalog of its own for the [neo4jDatabases] alone.
     */
    fun catalogs(
        fragments: List<Class<*>>,
        dimensions: VectorDimensionProvider?,
        declared: List<SchemaCatalog>,
        neo4jDatabases: Set<String>,
    ): List<SchemaCatalog> {
        val read = fragments.mapNotNull { fragment ->
            runCatching { fragment to SchemaCatalog.fromFragments(dimensions, fragment) }
                .onFailure {
                    logger.info(
                        "@NodeFragment {} is not read into the schema: {}. Register it in a SchemaCatalog bean of your own to manage its schema.",
                        fragment.name, it.message,
                    )
                }
                .getOrNull()
        }
        // Items conflict only on one label, so the fragments that declare a label's items are merged together, and
        // where they conflict, each of them is passed over.
        val clashing = read.flatMap { (fragment, catalog) -> catalog.items.map { it.label to (fragment to catalog) } }
            .groupBy({ it.first }, { it.second })
            .flatMap { (_, group) ->
                val readers = group.distinct()
                runCatching { SchemaCatalog.merge(readers.map { it.second }) }.fold({ emptyList<Class<*>>() }) {
                    logger.warn(
                        "The @NodeFragment classes {} declare conflicting schema, so none of theirs is applied from the scan: {}. " +
                            "Declare the items in a SchemaCatalog bean of your own, or set drivine.schema.scan-fragments=false.",
                        readers.map { reader -> reader.first.name }, it.message,
                    )
                    readers.map { reader -> reader.first }
                }
            }
            .toSet()
        val kept = read.filter { it.first !in clashing }.map { it.second }
        if (kept.isEmpty()) return emptyList()
        val scanned = SchemaCatalog.merge(kept).except(declared)
        val text = scanned.items.filterIsInstance<TextIndexSpec>()
        // The rest through except, not of, so its @NodeId defaults stay defaults: index-node-ids=false leaves them out,
        // and one an application's own uniqueness constraint over the id serves is left out beside it.
        val rest = scanned.except(listOf(SchemaCatalog.of(text)))
        if (text.isNotEmpty() && neo4jDatabases.isEmpty()) {
            logger.info("The TEXT indexes of the @NodeFragment classes scanned are not made: only Neo4j makes one, and no Neo4j database is registered: {}", text)
        }
        return listOfNotNull(
            rest.takeUnless { it.isEmpty() },
            SchemaCatalog.of(text).takeUnless { it.isEmpty() || neo4jDatabases.isEmpty() }?.forDatabases(*neo4jDatabases.toTypedArray()),
        )
    }
}
