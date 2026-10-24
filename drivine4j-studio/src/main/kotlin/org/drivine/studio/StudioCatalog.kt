package org.drivine.studio

import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.model.RelationshipModel
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter

/** What the studio can load: every `@GraphView` and `@NodeFragment` found, and what could not be read. */
data class StudioCatalog(
    val views: List<ViewDescriptor>,
    val fragments: List<FragmentDescriptor>,
    val problems: List<CatalogProblem>,
) {
    fun view(className: String): ViewDescriptor? = views.firstOrNull { it.className == className }
    fun fragment(className: String): FragmentDescriptor? = fragments.firstOrNull { it.className == className }

    companion object {
        /** The alias a query on a plain fragment gives its node, as the generated DSL does. */
        const val FRAGMENT_ALIAS = "n"

        /** Finds the annotated classes under [basePackages]. */
        fun scan(basePackages: Collection<String>): StudioCatalog {
            val scanner = object : ClassPathScanningCandidateComponentProvider(false) {
                // A sealed or abstract fragment is loadable too: loadAll dispatches to its subtypes.
                override fun isCandidateComponent(beanDefinition: AnnotatedBeanDefinition) =
                    beanDefinition.metadata.isIndependent
            }
            scanner.addIncludeFilter(AnnotationTypeFilter(GraphView::class.java))
            scanner.addIncludeFilter(AnnotationTypeFilter(NodeFragment::class.java))
            val classes = basePackages
                .flatMap { scanner.findCandidateComponents(it) }
                .mapNotNull { it.beanClassName }
                .distinct()
                .map { Class.forName(it, false, Thread.currentThread().contextClassLoader) }
            return of(classes)
        }

        /** Describes [classes]; a class whose model cannot be built is a [CatalogProblem], not a failure. */
        fun of(classes: Collection<Class<*>>): StudioCatalog {
            val views = mutableListOf<ViewDescriptor>()
            val fragments = linkedMapOf<String, FragmentDescriptor>()
            val problems = mutableListOf<CatalogProblem>()

            fun fragment(clazz: Class<*>): FragmentDescriptor =
                fragments.getOrPut(clazz.name) { describe(FragmentModel.from(clazz)) }

            classes.sortedBy { it.simpleName }.forEach { clazz ->
                try {
                    when {
                        clazz.isAnnotationPresent(GraphView::class.java) -> {
                            val model = GraphViewModel.from(clazz)
                            // A view's root and targets are in the catalogue whether or not they were scanned.
                            fragment(model.rootFragment.fragmentType)
                            model.relationships.mapNotNull { targetFragmentType(it) }.forEach { fragment(it) }
                            views += describe(model)
                        }
                        clazz.isAnnotationPresent(NodeFragment::class.java) -> fragment(clazz)
                    }
                } catch (e: Exception) {
                    problems += CatalogProblem(clazz.name, e.message ?: e.javaClass.simpleName)
                }
            }
            return StudioCatalog(views, fragments.values.sortedBy { it.name }, problems)
        }

        private fun describe(model: FragmentModel) = FragmentDescriptor(
            name = model.clazz.simpleName,
            className = model.className,
            labels = model.labels,
            idProperty = model.nodeIdField,
            // The model lists properties by name; the id leads, as the one every node is told apart by.
            properties = model.fields.sortedByDescending { it.name == model.nodeIdField }.map {
                PropertyDescriptor(it.name, it.kotlinType?.simpleName ?: it.type.simpleName, it.type.name, it.nullable, it.vectorIndexed)
            },
        )

        private fun describe(model: GraphViewModel) = ViewDescriptor(
            name = model.clazz.simpleName,
            className = model.className,
            root = RootDescriptor(model.rootFragment.fieldName, model.rootFragment.fragmentType.name),
            relationships = model.relationships.map { rel ->
                val target = if (rel.isRelationshipFragment) rel.targetNodeType ?: rel.elementType else rel.elementType
                RelationshipDescriptor(
                    field = rel.fieldName,
                    type = if (rel.isPath) rel.hops.joinToString("/") { it.type } else rel.type,
                    direction = rel.direction.name,
                    target = target.name,
                    targetIsView = target.isAnnotationPresent(GraphView::class.java),
                    // The properties a `related` condition may name: those of the node at the far end.
                    filterOn = targetFragmentType(rel)?.name?.takeUnless { rel.isRelationshipFragment },
                    collection = rel.isCollection,
                    nullable = rel.isNullable,
                    edgeProperties = rel.relationshipProperties,
                    recursive = rel.isRecursive,
                )
            },
            aggregates = model.aggregateFields.map { it.fieldName },
        )

        /** The fragment at the far end of [rel]: the target itself, or a target view's root. */
        private fun targetFragmentType(rel: RelationshipModel): Class<*>? {
            val target = if (rel.isRelationshipFragment) rel.targetNodeType else rel.elementType
            return when {
                target == null -> null
                target.isAnnotationPresent(NodeFragment::class.java) -> target
                target.isAnnotationPresent(GraphView::class.java) ->
                    runCatching { GraphViewModel.from(target).rootFragment.fragmentType }.getOrNull()
                else -> null
            }
        }
    }
}

data class ViewDescriptor(
    val name: String,
    val className: String,
    val root: RootDescriptor,
    val relationships: List<RelationshipDescriptor>,
    val aggregates: List<String>,
)

/** [field] is the alias the root's properties are read under; [fragment] names a [FragmentDescriptor]. */
data class RootDescriptor(val field: String, val fragment: String)

data class RelationshipDescriptor(
    val field: String,
    val type: String,
    val direction: String,
    val target: String,
    val targetIsView: Boolean,
    /** The fragment whose properties a condition on this relationship may name, or null when it takes none. */
    val filterOn: String?,
    val collection: Boolean,
    val nullable: Boolean,
    val edgeProperties: List<String>,
    val recursive: Boolean,
)

data class FragmentDescriptor(
    val name: String,
    val className: String,
    val labels: List<String>,
    val idProperty: String?,
    val properties: List<PropertyDescriptor>,
)

data class PropertyDescriptor(
    val name: String,
    val type: String,
    val javaType: String,
    val nullable: Boolean,
    val vector: Boolean,
)

data class CatalogProblem(val className: String, val message: String)
