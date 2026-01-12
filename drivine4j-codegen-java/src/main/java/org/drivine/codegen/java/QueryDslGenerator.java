package org.drivine.codegen.java;

import com.squareup.javapoet.*;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.*;

/**
 * Generates Java query DSL classes for @GraphView annotated classes.
 *
 * Uses the same underlying DSL model (WhereCondition, PropertyReference, etc.)
 * as the Kotlin generator, but creates Java-friendly builder APIs.
 */
public class QueryDslGenerator {

    private final Elements elementUtils;
    private final Types typeUtils;
    private final Filer filer;
    private final Messager messager;
    private final List<TypeElement> graphViewClasses;

    // Track generated properties classes to avoid duplicates
    private final Set<String> generatedPropertiesClasses = new HashSet<>();

    public QueryDslGenerator(
            Elements elementUtils,
            Types typeUtils,
            Filer filer,
            Messager messager,
            List<TypeElement> graphViewClasses) {
        this.elementUtils = elementUtils;
        this.typeUtils = typeUtils;
        this.filer = filer;
        this.messager = messager;
        this.graphViewClasses = graphViewClasses;
    }

    public void generateAll() {
        // First pass: collect all fragment types
        Map<String, FragmentInfo> allFragments = new LinkedHashMap<>();
        Map<TypeElement, List<ViewProperty>> viewStructures = new LinkedHashMap<>();

        for (TypeElement graphViewClass : graphViewClasses) {
            List<ViewProperty> structure = analyzeGraphViewStructure(graphViewClass);
            viewStructures.put(graphViewClass, structure);
            collectFragmentTypes(structure, allFragments);
        }

        // Generate properties classes for all fragments
        for (FragmentInfo fragmentInfo : allFragments.values()) {
            generatePropertiesClass(fragmentInfo);
        }

        // Generate QueryDsl class for each view
        for (TypeElement graphViewClass : graphViewClasses) {
            List<ViewProperty> structure = viewStructures.get(graphViewClass);
            generateQueryDslClass(graphViewClass, structure);
        }
    }

    /**
     * Analyzes a @GraphView class to extract its structure.
     */
    private List<ViewProperty> analyzeGraphViewStructure(TypeElement graphViewClass) {
        List<ViewProperty> properties = new ArrayList<>();

        for (Element member : graphViewClass.getEnclosedElements()) {
            if (member.getKind() != ElementKind.FIELD) {
                continue;
            }

            VariableElement field = (VariableElement) member;
            String fieldName = field.getSimpleName().toString();
            TypeMirror fieldType = field.asType();

            boolean isRoot = hasAnnotation(field, "org.drivine.annotation.Root");
            boolean isRelationship = hasAnnotation(field, "org.drivine.annotation.GraphRelationship");

            if (isRoot) {
                properties.add(new ViewProperty(fieldName, fieldType, true, false, null));
            } else if (isRelationship) {
                TypeMirror targetType = extractTargetType(fieldType);
                boolean isNestedView = hasAnnotation(targetType, "org.drivine.annotation.GraphView");
                properties.add(new ViewProperty(fieldName, targetType, false, true,
                    isNestedView ? getTypeElement(targetType) : null));
            }
        }

        return properties;
    }

    /**
     * Extracts element type from List<T> or Set<T>, or returns the type itself.
     */
    private TypeMirror extractTargetType(TypeMirror type) {
        if (type.getKind() == TypeKind.DECLARED) {
            DeclaredType declaredType = (DeclaredType) type;
            String typeName = declaredType.asElement().getSimpleName().toString();
            if ((typeName.equals("List") || typeName.equals("Set")) &&
                !declaredType.getTypeArguments().isEmpty()) {
                return declaredType.getTypeArguments().get(0);
            }
        }
        return type;
    }

    /**
     * Collects all fragment types from view properties.
     */
    private void collectFragmentTypes(List<ViewProperty> structure, Map<String, FragmentInfo> fragments) {
        for (ViewProperty prop : structure) {
            TypeElement typeElement = getTypeElement(prop.type);
            if (typeElement == null) continue;

            String qualifiedName = typeElement.getQualifiedName().toString();

            if (hasAnnotation(prop.type, "org.drivine.annotation.NodeFragment")) {
                fragments.putIfAbsent(qualifiedName, new FragmentInfo(typeElement, prop.name));
            } else if (prop.nestedViewClass != null) {
                // Recursively collect from nested views
                List<ViewProperty> nestedStructure = analyzeGraphViewStructure(prop.nestedViewClass);
                collectFragmentTypes(nestedStructure, fragments);
            }
        }
    }

    /**
     * Generates a Properties class for a fragment type.
     * Example: PersonProperties with name, bio, etc. property references.
     */
    private void generatePropertiesClass(FragmentInfo fragmentInfo) {
        TypeElement fragmentClass = fragmentInfo.fragmentClass;
        String simpleName = fragmentClass.getSimpleName().toString();
        String propertiesClassName = simpleName + "Properties";
        String packageName = getPackageName(fragmentClass);

        // Skip if already generated
        String fullName = packageName + "." + propertiesClassName;
        if (generatedPropertiesClasses.contains(fullName)) {
            return;
        }
        generatedPropertiesClasses.add(fullName);

        // NodeReference interface for instanceOf support
        ClassName nodeReference = ClassName.get("org.drivine.query.dsl", "NodeReference");
        ClassName propertyReference = ClassName.get("org.drivine.query.dsl", "PropertyReference");
        ClassName stringPropertyReference = ClassName.get("org.drivine.query.dsl", "StringPropertyReference");

        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(propertiesClassName)
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(nodeReference);

        // Add alias field and constructor
        classBuilder.addField(String.class, "alias", Modifier.PRIVATE, Modifier.FINAL);

        classBuilder.addMethod(MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(String.class, "alias")
            .addStatement("this.alias = alias")
            .build());

        // Implement NodeReference.getNodeAlias()
        classBuilder.addMethod(MethodSpec.methodBuilder("getNodeAlias")
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(Override.class)
            .returns(String.class)
            .addStatement("return alias")
            .build());

        // Generate property accessors for each field in the fragment
        for (Element member : fragmentClass.getEnclosedElements()) {
            if (member.getKind() != ElementKind.FIELD) continue;

            VariableElement field = (VariableElement) member;
            String fieldName = field.getSimpleName().toString();
            TypeMirror fieldType = field.asType();

            // Determine the property reference type
            TypeName propRefType;
            if (isStringType(fieldType)) {
                propRefType = stringPropertyReference;
            } else {
                propRefType = ParameterizedTypeName.get(propertyReference,
                    TypeName.get(fieldType).box());
            }

            // Generate getter method that returns a PropertyReference
            classBuilder.addMethod(MethodSpec.methodBuilder(fieldName)
                .addModifiers(Modifier.PUBLIC)
                .returns(propRefType)
                .addStatement("return new $T(alias, $S)", propRefType, fieldName)
                .build());
        }

        // Write the file
        try {
            JavaFile javaFile = JavaFile.builder(packageName, classBuilder.build())
                .addFileComment("Generated by Drivine4j Java codegen - do not modify")
                .build();
            javaFile.writeTo(filer);
            messager.printMessage(Diagnostic.Kind.NOTE,
                "Generated " + propertiesClassName + " in " + packageName);
        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "Failed to generate " + propertiesClassName + ": " + e.getMessage());
        }
    }

    /**
     * Generates the QueryDsl class for a @GraphView.
     * Example: PersonCareerQueryDsl with person and employmentHistory properties.
     */
    private void generateQueryDslClass(TypeElement graphViewClass, List<ViewProperty> structure) {
        String simpleName = graphViewClass.getSimpleName().toString();
        String dslClassName = simpleName + "QueryDsl";
        String packageName = getPackageName(graphViewClass);

        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(dslClassName)
            .addModifiers(Modifier.PUBLIC);

        // Add property fields for each part of the view
        for (ViewProperty prop : structure) {
            TypeElement typeElement = prop.nestedViewClass != null ?
                prop.nestedViewClass : getTypeElement(prop.type);
            if (typeElement == null) continue;

            String propTypeName = typeElement.getSimpleName().toString();
            String propertiesClassName = propTypeName + "Properties";
            // Use the fragment's package, not the GraphView's package
            String propertiesPackage = getPackageName(typeElement);
            ClassName propertiesType = ClassName.get(propertiesPackage, propertiesClassName);

            // Add field
            classBuilder.addField(FieldSpec.builder(propertiesType, prop.name)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .build());
        }

        // Constructor that initializes all properties with their aliases
        MethodSpec.Builder constructorBuilder = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC);

        for (ViewProperty prop : structure) {
            TypeElement typeElement = prop.nestedViewClass != null ?
                prop.nestedViewClass : getTypeElement(prop.type);
            if (typeElement == null) continue;

            String propTypeName = typeElement.getSimpleName().toString();
            String propertiesClassName = propTypeName + "Properties";
            // Use the fragment's package, not the GraphView's package
            String propertiesPackage = getPackageName(typeElement);
            ClassName propertiesType = ClassName.get(propertiesPackage, propertiesClassName);

            constructorBuilder.addStatement("this.$N = new $T($S)",
                prop.name, propertiesType, prop.name);
        }

        classBuilder.addMethod(constructorBuilder.build());

        // Add static INSTANCE field
        ClassName dslType = ClassName.get(packageName, dslClassName);
        classBuilder.addField(FieldSpec.builder(dslType, "INSTANCE")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .initializer("new $T()", dslType)
            .build());

        // Write the file
        try {
            JavaFile javaFile = JavaFile.builder(packageName, classBuilder.build())
                .addFileComment("Generated by Drivine4j Java codegen - do not modify")
                .build();
            javaFile.writeTo(filer);
            messager.printMessage(Diagnostic.Kind.NOTE,
                "Generated " + dslClassName + " in " + packageName);
        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "Failed to generate " + dslClassName + ": " + e.getMessage());
        }
    }

    // Helper methods

    private boolean hasAnnotation(Element element, String annotationName) {
        for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
            if (annotation.getAnnotationType().toString().equals(annotationName)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAnnotation(TypeMirror type, String annotationName) {
        TypeElement typeElement = getTypeElement(type);
        return typeElement != null && hasAnnotation(typeElement, annotationName);
    }

    private TypeElement getTypeElement(TypeMirror type) {
        if (type.getKind() == TypeKind.DECLARED) {
            return (TypeElement) ((DeclaredType) type).asElement();
        }
        return null;
    }

    private String getPackageName(TypeElement typeElement) {
        return elementUtils.getPackageOf(typeElement).getQualifiedName().toString();
    }

    private boolean isStringType(TypeMirror type) {
        return type.toString().equals("java.lang.String");
    }

    // Data classes

    private static class ViewProperty {
        final String name;
        final TypeMirror type;
        final boolean isRoot;
        final boolean isRelationship;
        final TypeElement nestedViewClass;

        ViewProperty(String name, TypeMirror type, boolean isRoot, boolean isRelationship,
                     TypeElement nestedViewClass) {
            this.name = name;
            this.type = type;
            this.isRoot = isRoot;
            this.isRelationship = isRelationship;
            this.nestedViewClass = nestedViewClass;
        }
    }

    private static class FragmentInfo {
        final TypeElement fragmentClass;
        final String aliasName;

        FragmentInfo(TypeElement fragmentClass, String aliasName) {
            this.fragmentClass = fragmentClass;
            this.aliasName = aliasName;
        }
    }
}