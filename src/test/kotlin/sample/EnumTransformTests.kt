package sample

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.drivine.utils.ObjectUtils
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(classes = [TestAppContext::class])
class EnumTransformTests @Autowired constructor(
    private val manager: PersistenceManager
) {

    enum class Priority {
        LOW,
        MEDIUM,
        HIGH,
        URGENT
    }

    enum class Status {
        OPEN,
        IN_PROGRESS,
        BLOCKED,
        COMPLETED,
        CANCELLED
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Task(
        val id: String,
        val title: String,
        val description: String?,
        val priority: Priority,
        val status: Status,
        val assignee: String?
    )

    @BeforeEach
    fun setupTestData() {
        // Clear existing test data
        manager.execute(QuerySpecification
            .withStatement("MATCH (t:Task) WHERE t.createdBy = 'enum-test' DELETE t"))

        // Create test tasks with various enum values
        manager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (t1:Task {
                        id: 'task-1',
                        title: 'Fix critical bug',
                        description: 'Production is down',
                        priority: 'URGENT',
                        status: 'IN_PROGRESS',
                        assignee: 'alice',
                        createdBy: 'enum-test'
                    })
                    CREATE (t2:Task {
                        id: 'task-2',
                        title: 'Write documentation',
                        description: null,
                        priority: 'LOW',
                        status: 'OPEN',
                        assignee: 'bob',
                        createdBy: 'enum-test'
                    })
                    CREATE (t3:Task {
                        id: 'task-3',
                        title: 'Design new feature',
                        description: 'Waiting on product approval',
                        priority: 'MEDIUM',
                        status: 'BLOCKED',
                        assignee: null,
                        createdBy: 'enum-test'
                    })
                    CREATE (t4:Task {
                        id: 'task-4',
                        title: 'Deploy to staging',
                        description: 'Ready to deploy',
                        priority: 'HIGH',
                        status: 'COMPLETED',
                        assignee: 'alice',
                        createdBy: 'enum-test'
                    })
                """.trimIndent())
        )
    }

    @Test
    fun `save and load tasks with enum properties`() {
        val tasks = manager.query(
            QuerySpecification
                .withStatement("""
                    MATCH (t:Task)
                    WHERE t.createdBy = 'enum-test'
                    RETURN properties(t) AS task
                    ORDER BY t.id
                """.trimIndent())
                .transform(Task::class.java)
        )

        println("Loaded tasks: $tasks")
        assertEquals(4, tasks.size)

        // Verify first task (URGENT priority, IN_PROGRESS status)
        val task1 = tasks[0]
        assertEquals("task-1", task1.id)
        assertEquals("Fix critical bug", task1.title)
        assertEquals("Production is down", task1.description)
        assertEquals(Priority.URGENT, task1.priority)
        assertEquals(Status.IN_PROGRESS, task1.status)
        assertEquals("alice", task1.assignee)

        // Verify second task (LOW priority, OPEN status)
        val task2 = tasks[1]
        assertEquals("task-2", task2.id)
        assertEquals(Priority.LOW, task2.priority)
        assertEquals(Status.OPEN, task2.status)
        assertEquals(null, task2.description)

        // Verify third task (MEDIUM priority, BLOCKED status, no assignee)
        val task3 = tasks[2]
        assertEquals("task-3", task3.id)
        assertEquals(Priority.MEDIUM, task3.priority)
        assertEquals(Status.BLOCKED, task3.status)
        assertEquals(null, task3.assignee)

        // Verify fourth task (HIGH priority, COMPLETED status)
        val task4 = tasks[3]
        assertEquals("task-4", task4.id)
        assertEquals(Priority.HIGH, task4.priority)
        assertEquals(Status.COMPLETED, task4.status)
    }

    @Test
    fun `filter tasks by enum values`() {
        val urgentTasks = manager.query(
            QuerySpecification
                .withStatement("""
                    MATCH (t:Task)
                    WHERE t.createdBy = 'enum-test'
                    RETURN properties(t) AS task
                    ORDER BY t.id
                """.trimIndent())
                .transform(Task::class.java)
                .filter { it.priority == Priority.URGENT }
        )

        println("Urgent tasks: $urgentTasks")
        assertEquals(1, urgentTasks.size)
        assertEquals("task-1", urgentTasks[0].id)
        assertEquals(Priority.URGENT, urgentTasks[0].priority)
    }

    @Test
    fun `filter completed tasks`() {
        val completedTasks = manager.query(
            QuerySpecification
                .withStatement("""
                    MATCH (t:Task)
                    WHERE t.createdBy = 'enum-test'
                    RETURN properties(t) AS task
                """.trimIndent())
                .transform(Task::class.java)
                .filter { it.status == Status.COMPLETED }
        )

        println("Completed tasks: $completedTasks")
        assertEquals(1, completedTasks.size)
        assertEquals("task-4", completedTasks[0].id)
    }

    @Test
    fun `create new task with enums and save it using ObjectUtils`() {
        val newTask = Task(
            id = "task-5",
            title = "Review pull request",
            description = "Review code changes for feature X",
            priority = Priority.MEDIUM,
            status = Status.OPEN,
            assignee = "charlie"
        )

        // Save the task using ObjectUtils.primitiveProps - this will test enum conversion
        val props = ObjectUtils.primitiveProps(newTask, includeNulls = false)
        println("Converted properties: $props")

        manager.execute(
            QuerySpecification
                .withStatement("""
                    MERGE (t:Task {id: ${'$'}props.id})
                    SET t += ${'$'}props
                    SET t.createdBy = 'enum-test'
                """.trimIndent())
                .bind(mapOf("props" to props))
        )

        // Load it back
        val loadedTask = manager.getOne(
            QuerySpecification
                .withStatement("""
                    MATCH (t:Task {id: ${'$'}id})
                    WHERE t.createdBy = 'enum-test'
                    RETURN properties(t) AS task
                """.trimIndent())
                .bind(mapOf("id" to "task-5"))
                .transform(Task::class.java)
        )

        println("Loaded task: $loadedTask")
        assertEquals(newTask.id, loadedTask.id)
        assertEquals(newTask.title, loadedTask.title)
        assertEquals(newTask.description, loadedTask.description)
        assertEquals(newTask.priority, loadedTask.priority)
        assertEquals(newTask.status, loadedTask.status)
        assertEquals(newTask.assignee, loadedTask.assignee)
    }

    @Test
    fun `group tasks by priority`() {
        val tasks = manager.query(
            QuerySpecification
                .withStatement("""
                    MATCH (t:Task)
                    WHERE t.createdBy = 'enum-test'
                    RETURN properties(t) AS task
                """.trimIndent())
                .transform(Task::class.java)
        )

        val groupedByPriority = tasks.groupBy { it.priority }

        println("Tasks by priority:")
        groupedByPriority.forEach { (priority, taskList) ->
            println("  $priority: ${taskList.map { it.title }}")
        }

        assertEquals(1, groupedByPriority[Priority.LOW]?.size)
        assertEquals(1, groupedByPriority[Priority.MEDIUM]?.size)
        assertEquals(1, groupedByPriority[Priority.HIGH]?.size)
        assertEquals(1, groupedByPriority[Priority.URGENT]?.size)

        assertTrue(groupedByPriority[Priority.LOW]?.any { it.title == "Write documentation" } == true)
        assertTrue(groupedByPriority[Priority.URGENT]?.any { it.title == "Fix critical bug" } == true)
    }

    @Test
    fun `query tasks by enum value in Cypher`() {
        // You can also filter in Cypher directly
        val openTasks = manager.query(
            QuerySpecification
                .withStatement("""
                    MATCH (t:Task)
                    WHERE t.createdBy = 'enum-test'
                      AND t.status = 'OPEN'
                    RETURN properties(t) AS task
                    ORDER BY t.priority DESC
                """.trimIndent())
                .transform(Task::class.java)
        )

        println("Open tasks: $openTasks")
        assertEquals(1, openTasks.size)
        assertEquals("task-2", openTasks[0].id)
        assertEquals(Status.OPEN, openTasks[0].status)
    }

    @Test
    fun `map task priority to display string`() {
        val tasks = manager.query(
            QuerySpecification
                .withStatement("""
                    MATCH (t:Task)
                    WHERE t.createdBy = 'enum-test'
                    RETURN properties(t) AS task
                    ORDER BY t.id
                """.trimIndent())
                .transform(Task::class.java)
                .map { task ->
                    val priorityLabel = when (task.priority) {
                        Priority.LOW -> "🟢 Low"
                        Priority.MEDIUM -> "🟡 Medium"
                        Priority.HIGH -> "🟠 High"
                        Priority.URGENT -> "🔴 Urgent"
                    }
                    "${task.title} - $priorityLabel"
                }
        )

        println("Task summaries:")
        tasks.forEach { println("  $it") }

        assertEquals(4, tasks.size)
        assertTrue(tasks[0].contains("🔴 Urgent"))
        assertTrue(tasks[1].contains("🟢 Low"))
        assertTrue(tasks[2].contains("🟡 Medium"))
        assertTrue(tasks[3].contains("🟠 High"))
    }
}