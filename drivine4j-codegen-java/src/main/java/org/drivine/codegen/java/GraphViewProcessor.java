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
import org.drivine.codegen.model.ModelFiles;

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
 *
 * It also writes the module's model file, for Drivine Studio: see [ModelFileGenerator]. For that it
 * runs on every compilation, a module with none of its annotations included, since a file before
 * this one may describe classes now removed. It claims no annotation, so other processors still see
 * every one.
 */
@SupportedAnnotationTypes("*")
@SupportedOptions({ModelFiles.ENABLED, ModelFiles.MODULE, ModelFiles.FILE})
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class GraphViewProcessor extends AbstractProcessor {

    private Elements elementUtils;
    private Types typeUtils;
    private Filer filer;
    private Messager messager;

    // The model file's entries, read as each round finds its classes and written once the last is over.
    private final Map<String, Map<String, Object>> fragmentEntries = new TreeMap<>();
    private final Map<String, Map<String, Object>> relationshipFragmentEntries = new TreeMap<>();
    private final Map<String, Map<String, Object>> viewEntries = new TreeMap<>();

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
        if (roundEnv.processingOver()) {
            new ModelFileGenerator(filer, messager, processingEnv.getOptions()).generate(
                new ArrayList<>(fragmentEntries.values()),
                new ArrayList<>(relationshipFragmentEntries.values()),
                new ArrayList<>(viewEntries.values()));
            return false;
        }
        readModel(roundEnv);
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

        return false;
    }

    /** Reads this round's classes for the model file, while their elements are this round's. */
    private void readModel(RoundEnvironment roundEnv) {
        ModelReader reader = new ModelReader(elementUtils, typeUtils);
        read(roundEnv, "org.drivine.annotation.NodeFragment", fragmentEntries, reader::fragment);
        read(roundEnv, "org.drivine.annotation.RelationshipFragment", relationshipFragmentEntries, reader::relationshipFragment);
        read(roundEnv, "org.drivine.annotation.GraphView", viewEntries, reader::view);
    }

    private void read(RoundEnvironment roundEnv, String annotation, Map<String, Map<String, Object>> entries,
                      java.util.function.Function<TypeElement, Map<String, Object>> reader) {
        TypeElement type = elementUtils.getTypeElement(annotation);
        if (type == null) return;
        for (Element element : roundEnv.getElementsAnnotatedWith(type)) {
            if (element instanceof TypeElement typeElement) {
                entries.put(ModelReader.id(typeElement), reader.apply(typeElement));
            }
        }
    }
}