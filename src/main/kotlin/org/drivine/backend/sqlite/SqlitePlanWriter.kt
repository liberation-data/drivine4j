package org.drivine.backend.sqlite

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.drivine.backend.sql.WriteReturns
import org.drivine.model.Stamps
import org.drivine.query.PatchOutcomes
import java.sql.ResultSet

/**
 * Carries out one plan of steps on a graph kept by SQLite, for [SqliteWriteFunction]: a port of
 * `<graph>_write` on Postgres, step for step, so a save stamps, checks and writes a node here as it does
 * there. What it works out, it works out over JSON in memory, and writes a row only where what the step
 * leaves differs from what it found.
 *
 * - A `node` step finds the node of its shape by its id, or makes it. A root that is gone, carries
 *   another stamp, or is there when the save may only make it, is stale: the plan stops, and nothing
 *   is written. A node that is made is written whole, with a stamp of its own; one that is found gets a
 *   new first token only where the step changes it, or where it has no stamp of two tokens yet.
 * - A `ref` step finds a node that must be there: one that is not stops the plan, which counts nothing.
 * - `link`, `unlink` and `cut` write a node's relationships; each node whose relationships changed gets
 *   a new second token once, at the end.
 * - A `patch` step writes to a node that is there, and only where it meets the query the plan was
 *   given, if any.
 *
 * Numbers are read and written as the decimals they are written as, so a property no step writes keeps
 * its text, and two values are the same where Postgres's `jsonb` says so: `1.5` is `1.50`.
 */
internal class SqlitePlanWriter(
    graph: SqliteGraph,
    private val statements: SqliteStatements,
    plan: String,
    shapes: String,
    private val mark: String,
    private val patchedIf: String?,
) {
    /** The plan stopped: a root is stale, or a node a step needs is not there. Nothing it wrote is kept. */
    class Stopped(val count: Long) : RuntimeException(null, null, false, false)

    private val nodesTable = graph.nodes
    private val edgesTable = graph.edges
    private val steps = json.readTree(plan) as ArrayNode
    private val shapes = (json.readTree(shapes) as ArrayNode).map { Shape(it["labels"].map(JsonNode::asText), it["key"].asText()) }

    /** The node each `node` and `ref` step saved or found, by the number the plan gives it. */
    private val nodes = HashMap<Int, Long>()

    /** The nodes whose stamps are handed back, in the order their steps came. */
    private val handed = mutableListOf<Handed>()

    /** The nodes whose relationships changed, each to get a new second token. */
    private val relinks = mutableListOf<Long>()

    private var root: Long? = null
    private var rootMade = false
    private var was: String? = null
    private var carried: String? = null
    private var count = 0L

    /** How a step finds a node: its labels, and the property its id is stored under. */
    private class Shape(val labels: List<String>, val key: String)

    /** A node as it is stored. */
    private class Row(val nid: Long, val labels: ArrayNode, val props: ObjectNode, val stamp: String?)

    /** A node whose stamp is handed back: under [index], with what was [found] of it. */
    private class Handed(val index: String, val found: String?, val nid: Long)

    /** Carries out the steps and gives back what [returns] asks; throws [Stopped] where the plan stops. */
    fun write(returns: WriteReturns): List<Any> {
        steps.forEach { step ->
            when (step["op"].asText()) {
                "node" -> node(step)
                "ref" -> ref(step)
                "link" -> link(step)
                "unlink" -> unlink(step)
                "cut" -> cut(step)
                "patch" -> patch(step)
                else -> throw IllegalArgumentException("A plan has a step drivine_write does not know: ${step["op"]}")
            }
        }
        // Each node whose relationships changed gets a new second token, once. A stamp that is not two
        // tokens, which no save leaves, is replaced whole.
        if (relinks.isNotEmpty()) {
            statements.update(
                "UPDATE $nodesTable SET stamp = COALESCE(substr(${twoTokensOf("stamp")}, 1, 17), substr(?, 1, 17)) || substr(?, 18) " +
                    "WHERE nid IN (SELECT value FROM json_each(?))",
                mark, mark, json.writeValueAsString(relinks.distinct()),
            )
        }
        return when (returns) {
            WriteReturns.STAMPS -> listOfNotNull(stamps())
            WriteReturns.EACH_STAMP -> {
                val stamps = currentStamps()
                handed.map { "${it.index}/${it.found.orEmpty()}=${stamps[it.nid].orEmpty()}" }
            }
            WriteReturns.COUNT -> listOf(count)
        }
    }

    /**
     * The stamps a save hands back: the root's, keeping the object's own token wherever the node had
     * another when the save began, then `index/found=stamp` for each node whose stamp is handed back.
     */
    private fun stamps(): String? {
        val stamps = currentStamps()
        var stamp = root?.let { stamps[it] } ?: return null
        val carried = carried
        val was = was
        if (carried == null) {
            // An object that carried no stamp is handed the whole stamp only of a node its save made.
            if (!rootMade) stamp = stamp.take(17)
        } else if (was != carried) {
            val first = if (equal(was?.take(16), carried.take(16))) stamp.take(16) else carried.take(16)
            val second = if (equal(was?.drop(17)?.ifEmpty { null }, carried.drop(17).ifEmpty { null }) || (rootMade && carried.drop(17).isEmpty())) {
                stamp.drop(17)
            } else {
                carried.drop(17)
            }
            stamp = "$first:$second"
        }
        return stamp + handed.joinToString("") { ",${it.index}/${it.found.orEmpty()}=${stamps[it.nid].orEmpty()}" }
    }

    /** Whether [a] and [b] are both there and the same: SQL's `=`, which a null never meets. */
    private fun equal(a: String?, b: String?): Boolean = a != null && b != null && a == b

    /** The stamp of each node a step saved or found, as it is now; a node deleted since has none. */
    private fun currentStamps(): Map<Long, String?> {
        val wanted = (handed.map { it.nid } + listOfNotNull(root)).distinct()
        if (wanted.isEmpty()) return emptyMap()
        return statements.rows("SELECT nid, stamp FROM $nodesTable WHERE nid IN (SELECT value FROM json_each(?))", json.writeValueAsString(wanted)) {
            it.getLong(1) to it.getString(2)
        }.toMap()
    }

    // ----- Steps -----

    /** Saves a node: found, or made. What the step writes is worked out over the node as it was found. */
    private fun node(step: JsonNode) {
        val shape = shapes[step["s"].asInt()]
        val expect = text(step, "expect")
        val fresh = step["fresh"].asText()
        var tries = 0
        var nid = 0L
        var made = false
        var found: String? = null
        while (true) {
            val row = find(shape, step["k"].asText())
            made = row == null
            var labels: ArrayNode
            var o: ObjectNode
            var stamp: String?
            if (row == null) {
                // A checked save does not make the node again: it is gone, and the object is stale. Nor does a step that makes no node.
                if (expect != null || !flag(step, "create", true)) throw Stopped(count)
                labels = step["labels"].deepCopy()
                o = json.createObjectNode().apply { set<JsonNode>(step["key"].asText(), step["id"]) }
                stamp = null
            } else if (flag(step, "absent", false)) {
                // A step that only makes the node writes nothing to one that is there.
                throw Stopped(count)
            } else {
                labels = row.labels
                o = row.props.deepCopy()
                stamp = row.stamp
            }
            found = stamp
            if (expect != null && (stamp == null || (if (flag(step, "whole", false)) stamp else stamp.take(16)) != expect)) throw Stopped(count)

            // The properties the step sets, and without those it unsets.
            setAndUnset(o, step)
            // The keys of each property bag the object no longer holds.
            step["bags"]?.forEach { bag ->
                o.fieldNames().asSequence().toList().forEach { key ->
                    if (!contains(bag["keep"], key) && owns(bag, key)) o.remove(key)
                }
            }

            // The labels an open label field holds are recorded on the node, so the node knows which are its
            // own. A step that clears replaces the record, and drops the labels recorded that the field no
            // longer holds, but for those of the class; one that does not adds to the record.
            val add = strings(step["add"])
            val drop = strings(step["drop"]).toMutableList()
            val owned = step["owned"]
            if (owned != null && !owned.isNull) {
                val property = owned["prop"].asText()
                val recorded = o[property]?.takeIf { it.isArray }?.map(JsonNode::asText).orEmpty()
                if (flag(owned, "clear", false)) {
                    val base = strings(owned["base"])
                    drop += recorded.filter { it !in add && it !in base }
                    if (add.isEmpty()) o.remove(property) else o.set<JsonNode>(property, strings(add))
                } else if (add.isNotEmpty()) {
                    o.set<JsonNode>(property, strings(recorded.filter { it !in add }.sortedWith(CODE_POINTS) + add))
                }
            }

            // The node's labels with those the step adds and without those it drops, in order; labels that
            // are as they were are not written again.
            var relabel = false
            if (add.isNotEmpty() || drop.isNotEmpty()) {
                val now = (labels.map(JsonNode::asText) + add).distinct().filter { it !in drop }.sortedWith(CODE_POINTS)
                relabel = now.toSet() != labels.map(JsonNode::asText).toSet()
                if (relabel || made) labels = strings(now)
            }

            if (made) {
                // A node that is made is written once, whole, with a stamp of its own. A uniqueness constraint
                // may refuse it: once, the node is looked for again, as on Postgres; a second time, the
                // constraint is on something other than this node's key, and refuses this save.
                val inserted = statements.rows(
                    "INSERT INTO $nodesTable (labels, props, stamp) VALUES (?, ?, ?) ON CONFLICT DO NOTHING RETURNING nid",
                    json.writeValueAsString(labels), json.writeValueAsString(o), fresh,
                ) { it.getLong(1) }.firstOrNull()
                if (inserted != null) {
                    nid = inserted
                    break
                }
                tries++
                if (tries > 1) {
                    statements.update("INSERT INTO $nodesTable (labels, props, stamp) VALUES (?, ?, ?)", json.writeValueAsString(labels), json.writeValueAsString(o), fresh)
                }
            } else {
                row!!
                nid = row.nid
                val same = same(o, row.props)
                // A new first token only if the step changes the node, or the node has no stamp of two tokens yet.
                val two = twoTokens(stamp)
                if (two == null || !same || relabel) {
                    stamp = fresh.take(16) + (two?.drop(16) ?: fresh.drop(16))
                    if (relabel) statements.update("UPDATE $nodesTable SET labels = ? WHERE nid = ?", json.writeValueAsString(labels), nid)
                    if (!same) {
                        statements.update("UPDATE $nodesTable SET props = ?, stamp = ? WHERE nid = ?", json.writeValueAsString(o), stamp, nid)
                    } else {
                        statements.update("UPDATE $nodesTable SET stamp = ? WHERE nid = ?", stamp, nid)
                    }
                }
                break
            }
        }

        nodes[step["r"].asInt()] = nid
        text(step, "i")?.let { index ->
            // Nothing for a node the step made, and a mark for one that was there without a stamp.
            handed += Handed(index, if (made) null else found ?: Stamps.NEVER_STAMPED, nid)
        }
        if (flag(step, "root", false)) {
            root = nid
            rootMade = made
            was = found
            carried = text(step, "carried")
        }
    }

    /** Finds a node that must be there. */
    private fun ref(step: JsonNode) {
        val row = find(shapes[step["s"].asInt()], step["k"].asText()) ?: throw Stopped(0)
        nodes[step["r"].asInt()] = row.nid
    }

    /** Joins two nodes. The relationship is as the step says only if every one of its type between them is. */
    private fun link(step: JsonNode) {
        val from = nodes.getValue(step["from"].asInt())
        val to = nodes.getValue(step["to"].asInt())
        val type = step["type"].asText()
        val direction = step["dir"].asText()
        val props = (step["props"] as? ObjectNode) ?: json.createObjectNode()
        val clear = strings(step["clear"])
        var seen = 0
        var unchanged = 0
        if (!flag(step, "always", false)) {
            // A node's relationship to itself is one relationship, whichever way it is read.
            val existing = statements.rows(
                "SELECT eid, props FROM $edgesTable WHERE type = ? AND ((? IN ('OUT', 'BOTH') AND src = ? AND dst = ?) " +
                    "OR ((? = 'IN' OR (? = 'BOTH' AND ? <> ?)) AND src = ? AND dst = ?)) ORDER BY eid",
                type, direction, from, to, direction, direction, from, to, to, from,
            ) { it.getLong(1) to objectOf(it.getString(2)) }
            existing.forEach { (eid, stored) ->
                seen++
                val written = stored.deepCopy().setAll<ObjectNode>(props)
                clear.forEach { written.remove(it) }
                if (same(written, stored)) {
                    unchanged++
                } else {
                    statements.update("UPDATE $edgesTable SET props = ? WHERE eid = ?", json.writeValueAsString(written), eid)
                }
            }
        }
        if (seen == 0) {
            // An undirected field is satisfied by a relationship either way, and makes one from the first node.
            val incoming = direction == "IN"
            statements.update(
                "INSERT INTO $edgesTable (src, dst, type, props) VALUES (?, ?, ?, ?)",
                if (incoming) to else from, if (incoming) from else to, type, json.writeValueAsString(props),
            )
        }
        if (seen == 0 || unchanged < seen) {
            relinks += from
            relinks += to
        }
        count += maxOf(seen, 1)
    }

    /** Removes a field's relationships to the targets whose ids the step names, or to every target but those. */
    private fun unlink(step: JsonNode) {
        val from = nodes.getValue(step["from"].asInt())
        val shape = shapes[step["s"].asInt()]
        val type = step["type"].asText()
        val direction = step["dir"].asText()
        val ids = strings(step["ids"]).toSet()
        val held = strings(step["held"]).toSet()
        val keep = flag(step, "keep", false)
        val delete = flag(step, "delete", false)
        val key = SqliteSql.property("t", shape.key)
        val labels = SqliteSql.hasLabels("t", shape.labels)?.let { " AND $it" }.orEmpty()
        // A relationship from the node to itself is read as leaving it, and so once when both directions are read.
        val reached = statements.rows(
            "SELECT e.eid, t.nid, $key FROM $edgesTable e JOIN $nodesTable t ON t.nid = e.dst " +
                "WHERE ? IN ('OUT', 'BOTH') AND e.src = ? AND e.type = ?$labels " +
                "UNION ALL " +
                "SELECT e.eid, t.nid, $key FROM $edgesTable e JOIN $nodesTable t ON t.nid = e.src " +
                "WHERE ? IN ('IN', 'BOTH') AND e.dst = ? AND e.type = ?$labels AND (? = 'IN' OR e.src <> ?)",
            direction, from, type, direction, from, type, direction, from,
        ) { Triple(it.getLong(1), it.getLong(2), it.getString(3)) }
        val gone = reached.filter { (_, _, id) -> id != null && (id in ids) != keep }
        if (gone.isEmpty()) return
        val targets = gone.map { it.second }
        for (target in targets.distinct().sorted()) {
            // A target gone since the relationships were read has nothing left to remove or count.
            if (statements.rows("SELECT 1 FROM $nodesTable WHERE nid = ?", target) { true }.isEmpty()) continue
            // What refers to a target is a relationship on the side this field reaches it from: counted before any is removed.
            var refs = 0L
            if (delete) {
                if (direction == "OUT" || direction == "BOTH") {
                    refs += statements.rows("SELECT count(*) FROM $edgesTable WHERE dst = ?", target) { it.getLong(1) }.single()
                }
                if (direction == "IN" || direction == "BOTH") {
                    // A relationship from the node to itself is counted once.
                    refs += statements.rows("SELECT count(*) FROM $edgesTable WHERE src = ? AND (? = 'IN' OR dst <> ?)", target, direction, target) { it.getLong(1) }.single()
                }
            }
            val removed = targets.count { it == target }.toLong()
            val id = gone.first { it.second == target }.third
            // The root is never deleted, and nor is a node the object still holds in another field.
            if (delete && target != from && refs == removed && id !in held) {
                relinks.removeAll { it == target }
                statements.update("DELETE FROM $nodesTable WHERE nid = ?", target)
            } else {
                relinks += target
            }
        }
        statements.update("DELETE FROM $edgesTable WHERE eid IN (SELECT value FROM json_each(?))", json.writeValueAsString(gone.map { it.first }))
        relinks += from
        count += gone.size
    }

    /** Removes the relationships of a type from one node to another, or with no other node named to any in a direction. */
    private fun cut(step: JsonNode) {
        val from = nodes.getValue(step["from"].asInt())
        val to = step["to"]?.takeUnless { it.isNull }?.let { nodes.getValue(it.asInt()) }
        val type = step["type"].asText()
        val direction = step["dir"].asText()
        val gone = statements.rows(
            "SELECT eid, dst FROM $edgesTable WHERE ? IN ('OUT', 'BOTH') AND src = ? AND type = ? AND (? IS NULL OR dst = ?) " +
                "UNION ALL " +
                "SELECT eid, src FROM $edgesTable WHERE ? IN ('IN', 'BOTH') AND dst = ? AND type = ? AND (? IS NULL OR src = ?) AND (? = 'IN' OR src <> ?)",
            direction, from, type, to, to, direction, from, type, to, to, direction, from,
        ) { it.getLong(1) to it.getLong(2) }
        if (gone.isEmpty()) return
        for (target in gone.map { it.second }.distinct().sorted()) {
            // An end gone since the relationships were read took them with it, and has no token to change.
            if (statements.rows("SELECT 1 FROM $nodesTable WHERE nid = ?", target) { true }.isNotEmpty()) relinks += target
        }
        statements.update("DELETE FROM $edgesTable WHERE eid IN (SELECT value FROM json_each(?))", json.writeValueAsString(gone.map { it.first }))
        relinks += from
        count += gone.size
    }

    /**
     * Writes properties to a node that is there, and makes none. One that is not there is left out of what
     * the call hands back; one that does not meet the query is left as it is, and said to.
     */
    private fun patch(step: JsonNode) {
        val row = find(shapes[step["s"].asInt()], step["k"].asText()) ?: return
        val fresh = step["fresh"].asText()
        val met = patchedIf?.let { query -> statements.rows(query, row.nid) { it.getLong(1) }.single() } ?: 1L
        val o = row.props.deepCopy()
        if (met > 0) setAndUnset(o, step)
        val same = same(o, row.props)
        val two = twoTokens(row.stamp)
        if (two == null || !same) {
            val stamp = fresh.take(16) + (two?.drop(16) ?: fresh.drop(16))
            if (!same) {
                statements.update("UPDATE $nodesTable SET props = ?, stamp = ? WHERE nid = ?", json.writeValueAsString(o), stamp, row.nid)
            } else {
                statements.update("UPDATE $nodesTable SET stamp = ? WHERE nid = ?", stamp, row.nid)
            }
        }
        val outcome = when {
            met == 0L -> PatchOutcomes.UNMET
            !same -> PatchOutcomes.CHANGED
            else -> PatchOutcomes.UNCHANGED
        }
        handed += Handed(step["i"].asText(), outcome, row.nid)
    }

    // ----- Reading -----

    /** The node of [shape] whose id is stored as [id], the first made where there are several; null where there is none. */
    private fun find(shape: Shape, id: String): Row? {
        val labels = SqliteSql.hasLabels("n", shape.labels)?.let { " AND $it" }.orEmpty()
        return statements.rows(
            "SELECT n.nid, n.labels, n.props, n.stamp FROM $nodesTable n WHERE ${SqliteSql.property("n", shape.key)} = ?$labels ORDER BY n.nid LIMIT 1",
            id,
        ) { row(it) }.firstOrNull()
    }

    private fun row(results: ResultSet) = Row(
        results.getLong(1),
        json.readTree(results.getString(2)) as ArrayNode,
        objectOf(results.getString(3)),
        results.getString(4),
    )

    /** [text] as a JSON object: properties are stored as one. */
    private fun objectOf(text: String?): ObjectNode = text?.let { json.readTree(it) as? ObjectNode } ?: json.createObjectNode()

    /** [o] with the properties [step] sets, and without those it unsets. */
    private fun setAndUnset(o: ObjectNode, step: JsonNode) {
        (step["set"] as? ObjectNode)?.let { o.setAll<ObjectNode>(it) }
        step["unset"]?.forEach { o.remove(it.asText()) }
    }

    /**
     * Whether the property bag [bag] owns the stored key [key]. A bag with a prefix owns the keys that begin
     * with it; a flat bag, each key that is no one else's.
     */
    private fun owns(bag: JsonNode, key: String): Boolean {
        val prefix = text(bag, "prefix")
        if (!flag(bag, "flat", false)) return key.length > (prefix?.length ?: 0) && (prefix == null || key.startsWith(prefix))
        if (contains(bag["reservedKeys"], key)) return false
        return strings(bag["reservedPrefixes"]).none { key.startsWith(it) }
    }

    // ----- JSON -----

    /** The text of the field [name] of [node]; null where it is absent or null. */
    private fun text(node: JsonNode, name: String): String? = node[name]?.takeUnless { it.isNull }?.asText()

    /** The boolean field [name] of [node], or [otherwise] where it is absent or null. */
    private fun flag(node: JsonNode, name: String, otherwise: Boolean): Boolean = node[name]?.takeUnless { it.isNull }?.asBoolean() ?: otherwise

    /** The strings of the array [node]; none where it is absent. */
    private fun strings(node: JsonNode?): List<String> = node?.takeIf { it.isArray }?.map(JsonNode::asText).orEmpty()

    /** [values] as a JSON array. */
    private fun strings(values: List<String>): ArrayNode = json.createArrayNode().apply { values.forEach { add(it) } }

    /** Whether the array [node] holds the string [value]. */
    private fun contains(node: JsonNode?, value: String): Boolean = node?.takeIf { it.isArray }?.any { it.isTextual && it.asText() == value } ?: false

    /** Whether [a] and [b] hold the same JSON, a number equal to a number however it is written. */
    private fun same(a: JsonNode, b: JsonNode): Boolean = a.equals(NUMBERS_BY_VALUE, b)

    /** [stamp] where it is two tokens, as every save leaves one, and null where it is not. */
    private fun twoTokens(stamp: String?): String? = stamp?.takeIf { it.length == 33 && it[16] == ':' }

    companion object {
        /** Reads numbers as the decimals they are written as, and writes them back the same. */
        val json: ObjectMapper = ObjectMapper()
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true))
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

        /**
         * Text in the order of its code points, which is the byte order Postgres's `COLLATE "C"` sorts labels in, and the order
         * Cypher compares texts in (see [SqliteCypherValues.order]).
         */
        internal val CODE_POINTS = Comparator<String> { a, b ->
            val x = a.codePoints().toArray()
            val y = b.codePoints().toArray()
            (0 until minOf(x.size, y.size)).firstOrNull { x[it] != y[it] }?.let { x[it].compareTo(y[it]) } ?: x.size.compareTo(y.size)
        }

        /** Two numbers are the same where their values are; anything else where it is equal. */
        private val NUMBERS_BY_VALUE = Comparator<JsonNode> { a, b ->
            if (a.isNumber && b.isNumber) a.decimalValue().compareTo(b.decimalValue()) else if (a == b) 0 else 1
        }

        /** The SQL that reads [stamp], a column, where it is two tokens, and null where it is not. */
        private fun twoTokensOf(stamp: String): String = "CASE WHEN length($stamp) = 33 AND substr($stamp, 17, 1) = ':' THEN $stamp END"
    }
}
