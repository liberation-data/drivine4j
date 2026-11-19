package org.drivine.migration

import org.drivine.cypher.store.migrate.Kept as ModuleKept
import org.drivine.cypher.store.migrate.LayoutMigration
import org.drivine.cypher.store.migrate.MigrationReport as ModuleReport
import org.drivine.cypher.store.migrate.Status as ModuleStatus
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * drivine4j's own types for a store layout migration (liberation-data/drivine4j#832): an application opts in to
 * [ExperimentalStoreLayoutMigration] alone, so no public signature names one of the shared module's, which are internal.
 */
@OptIn(ExperimentalStoreLayoutMigration::class)
class MigrationTypesTest {

    @Test
    fun `no public signature of the migration names a type of the shared module's`() {
        val types = listOf(StoreLayoutMigration::class.java, StoreLayoutMigration.Companion::class.java, Declaration::class.java, Kind::class.java, Kind.Companion::class.java, MigrationReport::class.java) +
            MigrationReport::class.java.declaredClasses.toList()
        val named = types.flatMap { type ->
            val members = type.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                .flatMap { listOf(it.genericReturnType) + it.genericParameterTypes } +
                type.declaredConstructors.filter { Modifier.isPublic(it.modifiers) }.flatMap { it.genericParameterTypes.toList() } +
                type.declaredFields.filter { Modifier.isPublic(it.modifiers) }.map { it.genericType }
            members.flatMap(::classesOf).filter { it.startsWith("org.drivine.cypher.") }.map { "${type.name}: $it" }
        }
        assertEquals(emptyList(), named)
    }

    @Test
    fun `each kind is the module's of the same word`() {
        assertEquals(LayoutMigration.Kind.entries.map { it.name to it.word }, Kind.entries.map { it.module.name to it.word })
        assertEquals(Kind.DATE_TIME, Kind.of("datetime"))
        assertEquals(null, Kind.of("moment"))
    }

    @Test
    fun `each status is the module's of the same word`() {
        assertEquals(ModuleStatus.entries.map { it.name to it.word }, MigrationReport.Status.entries.map { it.name to it.word })
    }

    @Test
    fun `a report is written as the module writes it, every part of it kept`() {
        val module = ModuleReport(
            status = ModuleStatus.COLLISIONS,
            message = "Keys collide.",
            tables = mapOf("nodes" to ModuleReport.Counted(3, 2), "edges" to ModuleReport.Counted(1, 0)),
            properties = mapOf(
                "Person\u0000born" to ModuleReport.PropertyReport(mapOf("temporal" to 2L), listOf(ModuleReport.InvalidValue("nodes", 7, "date", "\"soon\"", "\"soon\" is no date."))),
            ),
            observed = mapOf("Person\u0000nick" to mapOf("date" to 1L)),
            candidates = listOf(ModuleReport.Candidate("Person", "nick", "date", 1)),
            kept = listOf(ModuleKept("nodes", 7, listOf("Person"), "born", "date", "\"soon\"", "\"soon\" is no date.")),
            collisions = listOf(ModuleReport.Collision("Event", listOf("at"), "{\"count\":2}")),
            notes = listOf("No index is made."),
        )
        val report = MigrationReport.of(module)
        assertEquals(module.toMap(), report.toMap())
        assertEquals(MigrationReport.Status.COLLISIONS, report.status)
        assertEquals(module.invalidCount, report.invalidCount)
        assertEquals(listOf(MigrationReport.PropertyReport("Person", "born", mapOf("temporal" to 2L), listOf(MigrationReport.InvalidValue("nodes", 7, "date", "\"soon\"", "\"soon\" is no date.")))), report.properties)
        assertTrue(MigrationReport.of(ModuleReport()).let { it.status == null && it.tables.isEmpty() && it.properties.isEmpty() })
    }

    private fun classesOf(type: Type): List<String> = when (type) {
        is Class<*> -> listOf(type.componentType?.name ?: type.name)
        is ParameterizedType -> classesOf(type.rawType) + type.actualTypeArguments.flatMap(::classesOf)
        is WildcardType -> (type.upperBounds + type.lowerBounds).flatMap(::classesOf)
        else -> emptyList()
    }
}
