package org.drivine.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate

/**
 * KSP processor that generates query DSL classes and extension functions for @GraphView classes.
 *
 * For each @GraphView annotated class, generates:
 * 1. Query DSL class with property references for type-safe queries
 * 2. Extension function on GraphObjectManager for clean API
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
 * fun GraphObjectManager.loadAll(
 *     type: Class<RaisedAndAssignedIssue>,
 *     spec: GraphQuerySpec<RaisedAndAssignedIssueQueryDsl>.() -> Unit
 * ): List<RaisedAndAssignedIssue> {
 *     return loadAll(type, RaisedAndAssignedIssueQueryDsl.INSTANCE, spec)
 * }
 * ```
 */
class GraphViewProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    private var processedInRound = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        // Only process once per compilation round
        if (processedInRound) {
            return emptyList()
        }
        processedInRound = true

        // Find all classes annotated with @GraphView
        val graphViewSymbols = resolver
            .getSymbolsWithAnnotation("org.drivine.annotation.GraphView")
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        if (graphViewSymbols.isEmpty()) {
            return emptyList()
        }

        val unableToProcess = mutableListOf<KSAnnotated>()

        // Validate all views
        val validViews = graphViewSymbols.filter { graphView ->
            if (!graphView.validate()) {
                unableToProcess.add(graphView)
                false
            } else {
                true
            }
        }

        if (validViews.isEmpty()) {
            return unableToProcess
        }

        try {
            logger.info("Processing ${validViews.size} @GraphView classes")

            // Generate code for all views together
            val generator = QueryDslGenerator(
                codeGenerator = codeGenerator,
                logger = logger,
                graphViewClasses = validViews
            )

            generator.generateAll()

        } catch (e: Exception) {
            logger.error("Failed to process @GraphView classes: ${e.message}")
            logger.exception(e)
        }

        return unableToProcess
    }
}
