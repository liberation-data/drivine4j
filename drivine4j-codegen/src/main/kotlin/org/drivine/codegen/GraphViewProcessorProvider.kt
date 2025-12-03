package org.drivine.codegen

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

/**
 * KSP provider for the Drivine4j code generator.
 * This is the entry point that KSP uses to create the processor.
 */
class GraphViewProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return GraphViewProcessor(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger
        )
    }
}