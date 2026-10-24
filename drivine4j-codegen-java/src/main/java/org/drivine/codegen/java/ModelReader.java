package org.drivine.codegen.java;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a module's `@NodeFragment`, `@RelationshipFragment` and `@GraphView` classes as the model
 * file describes them, as the KSP processor's reader does for the same annotations, so that a Java
 * module and a Kotlin one describe a class alike. A Java class's properties are its fields, its own
 * and its superclasses', as the core library reads them.
 *
 * Each class gives a map whose keys are in the order the format's schema lists them, with a null
 * for a key the class has nothing for, which the writer leaves out.
 */
final class ModelReader {

    private static final String DRIVINE = "org.drivine.annotation.";

    /** The annotations that map a view's field to something other than its root. */
    private static final List<String> VIEW_MAPPINGS = List.of("GraphRelationship", "GraphPath", "Count", "Aggregate");

    /** Each index annotation and its kind in the format; the first wins when a field has several. */
    private static final String[][] INDEXES = {
        {"Unique", "unique"}, {"RangeIndex", "range"}, {"TextIndex", "text"}, {"FullTextIndex", "fullText"},
    };

    private static final Set<String> NULLABLE = Set.of("Nullable", "CheckForNull");
    private static final Set<String> NON_NULL = Set.of("NonNull", "Nonnull", "NotNull");

    private static final Map<String, String> SCALARS = Map.ofEntries(
        Map.entry("java.lang.String", "string"), Map.entry("java.lang.Character", "string"),
        Map.entry("java.lang.Integer", "int"), Map.entry("java.lang.Long", "int"), Map.entry("java.lang.Short", "int"),
        Map.entry("java.lang.Byte", "int"), Map.entry("java.math.BigInteger", "int"),
        Map.entry("java.lang.Float", "float"), Map.entry("java.lang.Double", "float"), Map.entry("java.math.BigDecimal", "float"),
        Map.entry("java.lang.Boolean", "boolean"),
        Map.entry("java.util.UUID", "uuid"),
        Map.entry("java.time.LocalDate", "date"),
        Map.entry("java.time.OffsetTime", "time"),
        Map.entry("java.time.LocalTime", "localTime"),
        Map.entry("java.time.Instant", "datetime"), Map.entry("java.time.ZonedDateTime", "datetime"),
        Map.entry("java.time.OffsetDateTime", "datetime"), Map.entry("java.util.Date", "datetime"),
        Map.entry("java.time.LocalDateTime", "localDatetime"),
        Map.entry("java.time.Duration", "duration"));

    private static final Set<String> COLLECTIONS = Set.of(
        "java.util.List", "java.util.ArrayList", "java.util.Set", "java.util.HashSet", "java.util.LinkedHashSet",
        "java.util.Collection", "java.lang.Iterable");

    private static final Set<String> MAPS = Set.of("java.util.Map", "java.util.HashMap", "java.util.LinkedHashMap");

    private final Elements elements;
    private final Types types;

    ModelReader(Elements elements, Types types) {
        this.elements = elements;
        this.types = types;
    }

    Map<String, Object> fragment(TypeElement type) {
        List<VariableElement> fields = storedFields(type);
        List<VariableElement> bags = new ArrayList<>();
        VariableElement labelSet = null;
        boolean stamp = false;
        List<VariableElement> plain = new ArrayList<>();
        for (VariableElement field : fields) {
            if (has(field, "PropertyBag") || has(field, "CompositeProperty")) bags.add(field);
            else if (has(field, "NodeLabels")) labelSet = labelSet == null ? field : labelSet;
            else if (has(field, "NodeStamp")) stamp = true;
            else plain.add(field);
        }

        // A class-level index over one property is that property's own.
        List<Map<String, Object>> composite = new ArrayList<>();
        Map<String, String> single = new LinkedHashMap<>();
        for (String[] index : INDEXES) {
            for (AnnotationMirror annotation : all(type, index[0])) {
                List<String> names = strings(annotation, "properties");
                if (names.size() == 1) single.putIfAbsent(names.get(0), index[1]);
                else if (names.size() > 1) composite.add(map("kind", index[1], "properties", names));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id(type));
        out.put("name", type.getSimpleName().toString());
        out.put("labels", labels(type));
        out.put("nodeId", fields.stream().filter(f -> has(f, "NodeId")).map(f -> f.getSimpleName().toString()).findFirst().orElse(null));
        out.put("stamp", stamp ? Boolean.TRUE : null);
        Subtypes subtypes = subtypes(type);
        out.put("subtypes", subtypes.ids.isEmpty() ? null : subtypes.ids);
        out.put("subtypesComplete", subtypes.complete ? null : Boolean.FALSE);
        out.put("labelSet", labelSet == null ? null : labelSet(labelSet));
        bags.sort(BY_NAME);
        out.put("bags", bags.isEmpty() ? null : bags.stream().map(this::bag).toList());
        plain.sort(BY_NAME);
        out.put("properties", plain.stream().map(f -> property(f, single.get(f.getSimpleName().toString()))).toList());
        out.put("indexes", composite.isEmpty() ? null : composite);
        return out;
    }

    Map<String, Object> relationshipFragment(TypeElement type) {
        List<VariableElement> fields = storedFields(type);
        VariableElement target = fields.stream().filter(f -> isFragment(f.asType()) || isView(f.asType())).findFirst().orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id(type));
        out.put("name", type.getSimpleName().toString());
        out.put("target", target == null ? null : map("field", target.getSimpleName().toString(), "fragment", id(element(target.asType()))));
        List<VariableElement> properties = new ArrayList<>(fields);
        properties.remove(target);
        properties.sort(BY_NAME);
        out.put("properties", properties.stream().map(f -> property(f, null)).toList());
        return out;
    }

    Map<String, Object> view(TypeElement type) {
        List<VariableElement> fields = fields(type);
        // A view marks its root; one that does not has the one fragment no annotation maps.
        VariableElement root = fields.stream().filter(f -> has(f, "Root")).findFirst()
            .orElseGet(() -> fields.stream()
                .filter(f -> VIEW_MAPPINGS.stream().noneMatch(m -> has(f, m)) && isFragment(f.asType()))
                .findFirst().orElse(null));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id(type));
        out.put("name", type.getSimpleName().toString());
        out.put("root", root == null ? null : map("field", root.getSimpleName().toString(), "fragment", id(element(root.asType()))));
        out.put("relationships", fields.stream()
            .filter(f -> has(f, "GraphRelationship") || has(f, "GraphPath"))
            .sorted(BY_NAME)
            .map(this::relationship)
            .toList());
        List<Map<String, Object>> aggregates = fields.stream().map(this::aggregate).filter(a -> a != null)
            .sorted(Comparator.comparing(a -> (String) a.get("field")))
            .toList();
        out.put("aggregates", aggregates.isEmpty() ? null : aggregates);
        return out;
    }

    private Map<String, Object> relationship(VariableElement field) {
        TypeMirror type = field.asType();
        boolean many = COLLECTIONS.contains(name(type));
        TypeMirror element = many ? argument(type, 0) : type;
        // As GraphViewModel reads it: a path wins over a @GraphRelationship beside it, and is not sorted.
        AnnotationMirror path = annotation(field, "GraphPath");
        AnnotationMirror related = path == null ? annotation(field, "GraphRelationship") : null;
        // A relationship fragment is read one hop away, whatever its maxDepth says.
        int maxDepth = related == null || element == null || isRelationshipFragment(element) ? 1 : (Integer) value(related, "maxDepth");
        // As GraphViewModel marks it: a path, and a list read over several hops, are never written.
        boolean readOnly = has(field, "ReadOnly") || path != null || (maxDepth > 1 && (element == null || !isView(element)));
        AnnotationMirror sortedBy = path == null ? annotation(field, "SortedBy") : null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("field", field.getSimpleName().toString());
        out.put("type", related == null ? null : value(related, "type"));
        out.put("direction", related == null ? null : enumName(value(related, "direction")));
        out.put("target", element == null ? null : id(element(element)));
        out.put("many", many);
        // A Java field says nothing of null, and the core library takes it as optional.
        out.put("required", false);
        out.put("maxDepth", maxDepth > 1 ? maxDepth : null);
        out.put("sortedBy", sortedBy == null ? null : map("property", value(sortedBy, "property"), "ascending", value(sortedBy, "ascending")));
        out.put("readOnly", readOnly ? Boolean.TRUE : null);
        out.put("path", path == null ? null : hops(path));
        return out;
    }

    private List<Map<String, Object>> hops(AnnotationMirror path) {
        List<Map<String, Object>> hops = new ArrayList<>();
        for (Object hop : list(value(path, "hops"))) {
            AnnotationMirror mirror = (AnnotationMirror) hop;
            String orderBy = blankToNull((String) value(mirror, "orderBy"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("type", value(mirror, "type"));
            out.put("direction", enumName(value(mirror, "direction")));
            out.put("label", blankToNull((String) value(mirror, "label")));
            out.put("orderBy", orderBy);
            out.put("ascending", orderBy == null ? null : value(mirror, "ascending"));
            hops.add(out);
        }
        return hops;
    }

    private Map<String, Object> aggregate(VariableElement field) {
        AnnotationMirror count = annotation(field, "Count");
        AnnotationMirror aggregate = annotation(field, "Aggregate");
        AnnotationMirror annotation = count != null ? count : aggregate;
        if (annotation == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("field", field.getSimpleName().toString());
        out.put("function", count != null ? "COUNT" : enumName(value(aggregate, "function")));
        out.put("type", value(annotation, "type"));
        out.put("direction", enumName(value(annotation, "direction")));
        out.put("property", aggregate == null ? null : blankToNull((String) value(aggregate, "property")));
        return out;
    }

    private Map<String, Object> property(VariableElement field, String classIndex) {
        TypeMirror type = field.asType();
        Typed typed = typed(type);
        boolean packed = has(field, "JsonPacked");
        AnnotationMirror graphProperty = annotation(field, "GraphProperty");
        String index = classIndex;
        for (String[] candidate : INDEXES) {
            if (has(field, candidate[0])) {
                index = candidate[1];
                break;
            }
        }
        AnnotationMirror vector = annotation(field, "VectorIndex");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", field.getSimpleName().toString());
        out.put("stored", graphProperty == null ? field.getSimpleName().toString() : value(graphProperty, "value"));
        // A packed value the format has no type for is JSON text, whatever it is in the code.
        out.put("type", packed && typed.type.equals("any") ? "json" : typed.type);
        out.put("of", typed.of);
        out.put("values", typed.values);
        out.put("nullable", nullable(field));
        out.put("whenAbsent", has(field, "Default") ? "default" : has(field, "EmptyWhenAbsent") ? "empty" : null);
        out.put("packed", packed ? "json" : null);
        out.put("index", index);
        out.put("vector", vector == null ? null : map("similarity", enumName(value(vector, "similarity"))));
        out.put("source", source(type));
        return out;
    }

    private Map<String, Object> bag(VariableElement field) {
        AnnotationMirror annotation = annotation(field, "PropertyBag");
        if (annotation == null) annotation = annotation(field, "CompositeProperty");
        boolean flat = (Boolean) value(annotation, "flat");
        String prefix = (String) value(annotation, "prefix");
        if (prefix.isEmpty()) prefix = field.getSimpleName().toString();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("field", field.getSimpleName().toString());
        // As FragmentModel resolves it: a flat bag stores each entry under its bare key.
        out.put("prefix", flat ? "" : prefix + value(annotation, "delimiter"));
        out.put("flat", flat ? Boolean.TRUE : null);
        out.put("type", elementType(field.asType(), 1));
        return out;
    }

    private Map<String, Object> labelSet(VariableElement field) {
        TypeMirror element = argument(field.asType(), 0);
        TypeElement declared = element == null ? null : element(element);
        return map("field", field.getSimpleName().toString(),
            "values", declared != null && declared.getKind() == ElementKind.ENUM ? enumConstants(declared) : null);
    }

    private record Subtypes(List<String> ids, boolean complete) {}

    /**
     * The fragments a load of [type] may give instead, and whether that is all of them. A sealed
     * class's permitted subclasses and a `@JsonSubTypes` list are known as the module builds; a class
     * open to subclassing may have more registered in code, with `registerSubtype`.
     */
    private Subtypes subtypes(TypeElement type) {
        for (AnnotationMirror annotation : type.getAnnotationMirrors()) {
            if (!annotation.getAnnotationType().toString().equals("com.fasterxml.jackson.annotation.JsonSubTypes")) continue;
            List<String> ids = new ArrayList<>();
            for (Object entry : list(value(annotation, "value"))) {
                Object subtype = value((AnnotationMirror) entry, "value");
                if (subtype instanceof TypeMirror mirror) ids.add(id(element(mirror)));
            }
            ids.sort(null);
            return new Subtypes(ids, true);
        }
        if (type.getModifiers().contains(Modifier.SEALED)) {
            List<String> ids = new ArrayList<>();
            for (TypeMirror permitted : type.getPermittedSubclasses()) {
                TypeElement subtype = element(permitted);
                if (subtype != null && has(subtype, "NodeFragment")) ids.add(id(subtype));
            }
            ids.sort(null);
            return new Subtypes(ids, true);
        }
        boolean open = type.getKind() == ElementKind.INTERFACE || !type.getModifiers().contains(Modifier.FINAL);
        return new Subtypes(List.of(), !open);
    }

    /**
     * The class's labels and those of every `@NodeFragment` it extends or implements, its own first,
     * then the inherited ones breadth first, the superclass before the interfaces, as
     * `FragmentModel.labelsFor` gathers them.
     */
    private List<String> labels(TypeElement type) {
        Set<String> labels = new LinkedHashSet<>();
        Set<String> visited = new HashSet<>();
        Deque<TypeElement> queue = new ArrayDeque<>(List.of(type));
        while (!queue.isEmpty()) {
            TypeElement current = queue.removeFirst();
            if (!visited.add(id(current))) continue;
            AnnotationMirror fragment = annotation(current, "NodeFragment");
            if (fragment != null) labels.addAll(strings(fragment, "labels"));
            TypeElement superclass = element(current.getSuperclass());
            if (superclass != null && !superclass.getQualifiedName().contentEquals("java.lang.Object")) queue.add(superclass);
            for (TypeMirror face : current.getInterfaces()) {
                TypeElement element = element(face);
                if (element != null) queue.add(element);
            }
        }
        return new ArrayList<>(labels);
    }

    /** The fields a class stores: every instance field, its own and its superclasses', but `@GraphTransient` ones. */
    private List<VariableElement> storedFields(TypeElement type) {
        return fields(type).stream().filter(f -> !has(f, "GraphTransient")).toList();
    }

    /** The class's instance fields and its superclasses', a field hiding one above it in its place. */
    private List<VariableElement> fields(TypeElement type) {
        Map<String, VariableElement> fields = new LinkedHashMap<>();
        for (TypeElement current = type; current != null && !current.getQualifiedName().contentEquals("java.lang.Object");
             current = element(current.getSuperclass())) {
            for (Element member : current.getEnclosedElements()) {
                if (member.getKind() == ElementKind.FIELD && !member.getModifiers().contains(Modifier.STATIC)) {
                    fields.putIfAbsent(member.getSimpleName().toString(), (VariableElement) member);
                }
            }
        }
        return new ArrayList<>(fields.values());
    }

    private Object nullable(VariableElement field) {
        if (field.asType().getKind().isPrimitive()) return false;
        List<AnnotationMirror> annotations = new ArrayList<>(field.getAnnotationMirrors());
        annotations.addAll(field.asType().getAnnotationMirrors());
        for (AnnotationMirror annotation : annotations) {
            String simple = annotation.getAnnotationType().asElement().getSimpleName().toString();
            if (NULLABLE.contains(simple)) return true;
            if (NON_NULL.contains(simple)) return false;
        }
        return "unknown";
    }

    private record Typed(String type, String of, List<String> values) {}

    private Typed typed(TypeMirror type) {
        switch (type.getKind()) {
            case INT, LONG, SHORT, BYTE: return new Typed("int", null, null);
            case FLOAT, DOUBLE: return new Typed("float", null, null);
            case BOOLEAN: return new Typed("boolean", null, null);
            case CHAR: return new Typed("string", null, null);
            case ARRAY: return new Typed("list", typed(((ArrayType) type).getComponentType()).type, null);
            default: break;
        }
        TypeElement element = element(type);
        if (element == null) return new Typed("any", null, null);
        if (element.getKind() == ElementKind.ENUM) return new Typed("enum", null, enumConstants(element));
        String name = element.getQualifiedName().toString();
        if (SCALARS.containsKey(name)) return new Typed(SCALARS.get(name), null, null);
        if (COLLECTIONS.contains(name)) return new Typed("list", elementType(type, 0), null);
        if (MAPS.contains(name)) return new Typed("map", elementType(type, 1), null);
        return new Typed("any", null, null);
    }

    private String elementType(TypeMirror type, int index) {
        TypeMirror argument = argument(type, index);
        return argument == null ? "any" : typed(argument).type;
    }

    /** The type as Java writes it, without the annotations a type may carry. */
    private String source(TypeMirror type) {
        if (type.getKind().isPrimitive()) return type.getKind().name().toLowerCase();
        if (type.getKind() == TypeKind.ARRAY) return source(((ArrayType) type).getComponentType()) + "[]";
        TypeElement element = element(type);
        if (element == null) return types.erasure(type).toString();
        String name = element.getQualifiedName().toString();
        List<? extends TypeMirror> arguments = ((DeclaredType) type).getTypeArguments();
        if (arguments.isEmpty()) return name;
        List<String> parts = new ArrayList<>();
        for (TypeMirror argument : arguments) {
            parts.add(argument.getKind() == TypeKind.WILDCARD ? "?" : source(argument));
        }
        return name + "<" + String.join(", ", parts) + ">";
    }

    private List<String> enumConstants(TypeElement type) {
        List<String> constants = new ArrayList<>();
        for (Element member : type.getEnclosedElements()) {
            if (member.getKind() == ElementKind.ENUM_CONSTANT) constants.add(member.getSimpleName().toString());
        }
        return constants;
    }

    private TypeMirror argument(TypeMirror type, int index) {
        if (!(type instanceof DeclaredType declared)) return null;
        List<? extends TypeMirror> arguments = declared.getTypeArguments();
        if (index >= arguments.size()) return null;
        TypeMirror argument = arguments.get(index);
        if (argument.getKind() == TypeKind.WILDCARD) {
            TypeMirror bound = ((javax.lang.model.type.WildcardType) argument).getExtendsBound();
            return bound;
        }
        return argument;
    }

    private boolean isFragment(TypeMirror type) {
        TypeElement element = element(type);
        return element != null && has(element, "NodeFragment");
    }

    private boolean isView(TypeMirror type) {
        TypeElement element = element(type);
        return element != null && has(element, "GraphView");
    }

    private boolean isRelationshipFragment(TypeMirror type) {
        TypeElement element = element(type);
        return element != null && has(element, "RelationshipFragment");
    }

    private TypeElement element(TypeMirror type) {
        if (type == null || type.getKind() != TypeKind.DECLARED) return null;
        return (TypeElement) ((DeclaredType) type).asElement();
    }

    private String name(TypeMirror type) {
        TypeElement element = element(type);
        return element == null ? null : element.getQualifiedName().toString();
    }

    static String id(TypeElement type) {
        return type.getQualifiedName().toString();
    }

    private static boolean has(Element element, String name) {
        return annotation(element, name) != null;
    }

    private static AnnotationMirror annotation(Element element, String name) {
        for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
            if (annotation.getAnnotationType().toString().equals(DRIVINE + name)) return annotation;
        }
        return null;
    }

    /** Every [name] annotation on [element], those a repeated one is gathered into included. */
    private List<AnnotationMirror> all(Element element, String name) {
        List<AnnotationMirror> found = new ArrayList<>();
        for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
            String type = annotation.getAnnotationType().toString();
            if (type.equals(DRIVINE + name)) found.add(annotation);
            else if (type.equals(DRIVINE + name + ".Container")) {
                for (Object each : list(value(annotation, "value"))) found.add((AnnotationMirror) each);
            }
        }
        return found;
    }

    /** An annotation's [key], or the default its declaration gives it, unwrapped. */
    private Object value(AnnotationMirror annotation, String key) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
            elements.getElementValuesWithDefaults(annotation).entrySet()) {
            if (entry.getKey().getSimpleName().contentEquals(key)) return entry.getValue().getValue();
        }
        return null;
    }

    private List<String> strings(AnnotationMirror annotation, String key) {
        List<String> out = new ArrayList<>();
        for (Object each : list(value(annotation, key))) out.add((String) each);
        return out;
    }

    /** The values of an array an annotation holds, each unwrapped. */
    private static List<Object> list(Object value) {
        List<Object> out = new ArrayList<>();
        if (value instanceof List<?> items) {
            for (Object item : items) out.add(item instanceof AnnotationValue v ? v.getValue() : item);
        }
        return out;
    }

    private static String enumName(Object value) {
        return value instanceof VariableElement constant ? constant.getSimpleName().toString() : value == null ? null : value.toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static Map<String, Object> map(Object... entries) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) out.put((String) entries[i], entries[i + 1]);
        return out;
    }

    private static final Comparator<VariableElement> BY_NAME = Comparator.comparing(f -> f.getSimpleName().toString());
}
