package org.drivine.codegen.java;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.*;

/**
 * Java annotation processor for generating type-safe query DSL for @GraphView classes.
 *
 * This processor generates the same DSL model objects as the Kotlin KSP processor,
 * but with a Java-friendly builder API.
 *
 * For each @GraphView, generates:
 * 1. Properties classes for each fragment type (e.g., PersonProperties)
 * 2. QueryDsl class that aggregates all property references
 * 3. Static factory methods for building queries
 */
@SupportedAnnotationTypes({
    "org.drivine.annotation.GraphView",
    "org.drivine.annotation.NodeFragment"
})
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class GraphViewProcessor extends AbstractProcessor {

    private Elements elementUtils;
    private Types typeUtils;
    private Filer filer;
    private Messager messager;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        elementUtils = processingEnv.getElementUtils();
        typeUtils = processingEnv.getTypeUtils();
        filer = processingEnv.getFiler();
        messager = processingEnv.getMessager();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (annotations.isEmpty()) {
            return false;
        }

        // Find @GraphView annotation
        TypeElement graphViewAnnotation = elementUtils.getTypeElement("org.drivine.annotation.GraphView");
        if (graphViewAnnotation == null) {
            messager.printMessage(Diagnostic.Kind.NOTE, "GraphView annotation not found on classpath");
            return false;
        }

        // Collect all @GraphView annotated classes
        List<TypeElement> graphViewClasses = new ArrayList<>();
        for (Element element : roundEnv.getElementsAnnotatedWith(graphViewAnnotation)) {
            if (element.getKind() == ElementKind.CLASS) {
                graphViewClasses.add((TypeElement) element);
            }
        }

        // Also collect @NodeFragment classes for standalone fragment query DSLs.
        List<TypeElement> fragmentClasses = new ArrayList<>();
        TypeElement nodeFragmentAnnotation = elementUtils.getTypeElement("org.drivine.annotation.NodeFragment");
        if (nodeFragmentAnnotation != null) {
            for (Element element : roundEnv.getElementsAnnotatedWith(nodeFragmentAnnotation)) {
                if (element.getKind() == ElementKind.CLASS || element.getKind() == ElementKind.INTERFACE) {
                    fragmentClasses.add((TypeElement) element);
                }
            }
        }

        if (graphViewClasses.isEmpty() && fragmentClasses.isEmpty()) {
            return false;
        }

        messager.printMessage(Diagnostic.Kind.NOTE,
            "Drivine4j Java codegen: Processing " + graphViewClasses.size() + " @GraphView and "
                + fragmentClasses.size() + " @NodeFragment classes");

        // Generate DSL code
        QueryDslGenerator generator = new QueryDslGenerator(
            elementUtils, typeUtils, filer, messager, graphViewClasses, fragmentClasses
        );
        generator.generateAll();

        return true;
    }
}