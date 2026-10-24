package org.drivine.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate
import org.drivine.codegen.model.ModelFileGenerator

/**
 * KSP processor that generates query DSL classes and extension functions for @GraphView classes.
 *
 * For each @GraphView annotated class, generates:
 * 1. Query DSL class with property references for type-safe queries
 * 2. Extension function on GraphObjectOperations for clean API
 *
 * Example generated output for RaisedAndAssignedIssue:
 * ```kotlin
 * // Generated query DSL
 * class RaisedAndAssignedIssueQueryDsl {
 *     val issue = IssueProperties()
 *     val assignedTo = PersonProperties("assignedTo")
 *     val raisedBy = PersonProperties("raisedBy")
 *
 *     companion object {
 *         val INSTANCE = RaisedAndAssignedIssueQueryDsl()
 *     }
 * }
 *
 * // Generated extension function
 * fun GraphObjectOperations.loadAll(
 *     type: Class<RaisedAndAssignedIssue>,
 *     spec: GraphQuerySpec<RaisedAndAssignedIssueQueryDsl>.() -> Unit
 * ): List<RaisedAndAssignedIssue> {
 *     return loadAll(type, RaisedAndAssignedIssueQueryDsl.INSTANCE, spec)
 * }
 * ```
 *
 * It also writes the module's model file, for Drivine Studio: see [ModelFileGenerator].
 */
class GraphViewProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String> = emptyMap(),
) : SymbolProcessor {

    private var processedInRound = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        // Only process once per compilation round
        if (processedInRound) {
            return emptyList()
        }
        processedInRound = true

        val unableToProcess = mutableListOf<KSAnnotated>()

        // Find all classes annotated with @GraphView
        val graphViewSymbols = resolver
            .getSymbolsWithAnnotation("org.drivine.annotation.GraphView")
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        val validViews = graphViewSymbols.filter { graphView ->
            if (!graphView.validate()) {
                unableToProcess.add(graphView)
                false
            } else {
                true
            }
        }

        // Also generate a standalone query DSL for every @NodeFragment, so a bare fragment is queryable
        // like a view. (A fragment that is a view's root is still fine — its <F>QueryDsl is a separate
        // file from the view's <View>QueryDsl and the shared <F>Properties.)
        val fragmentSymbols = resolver
            .getSymbolsWithAnnotation("org.drivine.annotation.NodeFragment")
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        val validFragments = fragmentSymbols.filter { fragment ->
            if (!fragment.validate()) {
                unableToProcess.add(fragment)
                false
            } else {
                true
            }
        }

        // Relationship fragments have no query DSL of their own, but the model file describes them.
        val relationshipFragments = resolver
            .getSymbolsWithAnnotation("org.drivine.annotation.RelationshipFragment")
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.validate() }
            .toList()

        if (validViews.isEmpty() && validFragments.isEmpty() && relationshipFragments.isEmpty()) {
            return unableToProcess
        }

        try {
            logger.info("Processing ${validViews.size} @GraphView and ${validFragments.size} @NodeFragment classes")

            val generator = QueryDslGenerator(
                codeGenerator = codeGenerator,
                logger = logger,
                graphViewClasses = validViews,
                fragmentClasses = validFragments,
            )

            generator.generateAll()

            ModelFileGenerator(codeGenerator, logger, options).generate(validFragments, relationshipFragments, validViews)

        } catch (e: Exception) {
            logger.error("Failed to process @GraphView / @NodeFragment classes: ${e.message}")
            logger.exception(e)
        }

        return unableToProcess
    }
}
