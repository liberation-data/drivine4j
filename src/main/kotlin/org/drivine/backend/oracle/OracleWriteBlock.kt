package org.drivine.backend.oracle

import org.drivine.backend.oracle.OracleSql.hasLabels
import org.drivine.backend.oracle.OracleSql.path

/** How a block finds a node: the labels it carries and the property its id is stored under. */
internal data class NodeShape(val labels: List<String>, val key: String)

/**
 * The one block that writes to a graph kept by Oracle. It is given a plan, a JSON array of steps, and
 * carries them out in turn; what a save, `relate` or `unrelate` writes is in the plan, so the block's
 * text depends only on the kinds of node the plan names, and the database compiles it once for each.
 *
 * A block is one statement: one that fails is rolled back to where it began, so a save is applied
 * whole or not at all, with or without a transaction around it.
 *
 * The steps, each an object with its kind under `op`:
 *
 * - `node` saves a node: found by its labels and id and locked, or made. `set` holds the properties to
 *   write and `unset` those to remove; `add` and `drop` are labels. With `expect`, the node must carry
 *   that stamp, or the block returns no row and writes nothing. The node gets a new first token only
 *   if the step changes it.
 * - `ref` finds a node that must be there, and locks it.
 * - `link` joins two nodes; `unlink` removes a field's relationships to some of its targets; `cut` and
 *   `cutAll` remove relationships by type. Each gives the nodes at both ends a new second token when
 *   it changes what joins them.
 *
 * A node is locked before it is read, so what a step compares is what the last writer committed; a
 * relationship is written only by a step that holds the lock of the node at each end.
 *
 * @param stamps whether the block returns the stamps a save hands back, or the number of
 *   relationships its steps counted
 */
internal class OracleWriteBlock(private val graph: OracleGraph, private val shapes: List<NodeShape>, private val stamps: Boolean) {

    /** The statement. Its parameters are [PLAN] and [MARK]. */
    fun text(): String = """
DECLARE
    TYPE t_numbers IS TABLE OF NUMBER INDEX BY PLS_INTEGER;
    TYPE t_texts IS TABLE OF VARCHAR2(4000) INDEX BY PLS_INTEGER;
    TYPE t_set IS TABLE OF BOOLEAN INDEX BY VARCHAR2(4000);
    TYPE t_counts IS TABLE OF PLS_INTEGER INDEX BY VARCHAR2(40);
    TYPE t_ids IS TABLE OF NUMBER;
    TYPE t_keys IS TABLE OF VARCHAR2(4000);
    v_plan    JSON_ARRAY_T := JSON_ARRAY_T.parse(${'$'}$PLAN);
    v_mark    VARCHAR2(33) := ${'$'}$MARK;
    v_op      JSON_OBJECT_T;
    v_nodes   t_numbers;
    v_h_index t_texts;
    v_h_found t_texts;
    v_h_node  t_numbers;
    v_root    NUMBER;
    v_was     VARCHAR2(33);
    v_carried VARCHAR2(33);
    v_stamp   VARCHAR2(33);
    v_count   NUMBER := 0;
    v_result  CLOB;
    v_rows    SYS_REFCURSOR;
    e_stale   EXCEPTION;
    e_absent  EXCEPTION;

    -- Finds the node of a shape by its id, and locks it. A null o_nid says there is none.
    PROCEDURE find(p_shape NUMBER, p_id VARCHAR2, o_nid OUT NUMBER, o_stamp OUT VARCHAR2, o_labels OUT VARCHAR2, o_props OUT CLOB) IS
    BEGIN
        o_nid := NULL;
        CASE p_shape
${shapes.withIndex().joinToString("\n") { (index, shape) -> finder(index, shape) }}
        END CASE;
    END;

    -- The targets of a shape that a node is joined to by relationships of a type, with each relationship.
    PROCEDURE joined(p_shape NUMBER, p_from NUMBER, p_type VARCHAR2, p_dir VARCHAR2, o_eids OUT t_ids, o_nids OUT t_ids, o_keys OUT t_keys) IS
    BEGIN
        CASE p_shape
${shapes.withIndex().joinToString("\n") { (index, shape) -> joiner(index, shape) }}
        END CASE;
    END;

    -- Gives a node a new second token: a relationship of it was made, removed or changed.
    PROCEDURE relink(p_nid NUMBER) IS
    BEGIN
        UPDATE ${graph.nodes} SET stamp = NVL(SUBSTR(stamp, 1, 17), SUBSTR(v_mark, 1, 17)) || SUBSTR(v_mark, 18) WHERE nid = p_nid;
    END;

    FUNCTION members(p_array JSON_ARRAY_T) RETURN t_set IS
        v_set t_set;
    BEGIN
        IF p_array IS NOT NULL THEN
            FOR i IN 0 .. p_array.get_size - 1 LOOP
                v_set(p_array.get_string(i)) := TRUE;
            END LOOP;
        END IF;
        RETURN v_set;
    END;

    -- Whether a property bag owns a stored key: by its prefix, or for a flat bag by its being no one else's.
    FUNCTION owns(p_bag JSON_OBJECT_T, p_key VARCHAR2) RETURN BOOLEAN IS
        v_prefix   VARCHAR2(4000) := p_bag.get_string('prefix');
        v_reserved JSON_ARRAY_T;
    BEGIN
        IF NOT NVL(p_bag.get_boolean('flat'), FALSE) THEN
            RETURN LENGTH(p_key) > NVL(LENGTH(v_prefix), 0) AND (v_prefix IS NULL OR SUBSTR(p_key, 1, LENGTH(v_prefix)) = v_prefix);
        END IF;
        IF members(p_bag.get_array('reservedKeys')).EXISTS(p_key) THEN
            RETURN FALSE;
        END IF;
        v_reserved := p_bag.get_array('reservedPrefixes');
        IF v_reserved IS NOT NULL THEN
            FOR i IN 0 .. v_reserved.get_size - 1 LOOP
                IF SUBSTR(p_key, 1, LENGTH(v_reserved.get_string(i))) = v_reserved.get_string(i) THEN
                    RETURN FALSE;
                END IF;
            END LOOP;
        END IF;
        RETURN TRUE;
    END;

    PROCEDURE save_node(op JSON_OBJECT_T) IS
        v_nid       NUMBER;
        v_stamp     VARCHAR2(33);
        v_found     VARCHAR2(33);
        v_labels    VARCHAR2(4000);
        v_props     CLOB;
        v_new_props CLOB;
        v_expect    VARCHAR2(33) := op.get_string('expect');
        v_fresh     VARCHAR2(33) := op.get_string('fresh');
        v_o         JSON_OBJECT_T;
        v_set       JSON_OBJECT_T := op.get_object('set');
        v_owned     JSON_OBJECT_T := op.get_object('owned');
        v_bags      JSON_ARRAY_T := op.get_array('bags');
        v_array     JSON_ARRAY_T;
        v_keys      JSON_KEY_LIST;
        v_have      t_set;
        v_keep      t_set;
        v_add       t_set := members(op.get_array('add'));
        v_drop      t_set := members(op.get_array('drop'));
        v_recorded  t_set;
        v_label     VARCHAR2(4000);
        v_relabel   BOOLEAN := FALSE;
        v_same      NUMBER;
    BEGIN
        LOOP
            find(op.get_number('s'), op.get_string('k'), v_nid, v_stamp, v_labels, v_props);
            EXIT WHEN v_nid IS NOT NULL;
            -- A checked save does not make the node again: it is gone, and the object is stale.
            IF v_expect IS NOT NULL THEN
                RAISE e_stale;
            END IF;
            v_labels := op.get_array('labels').to_string;
            v_o := JSON_OBJECT_T();
            v_o.put(op.get_string('key'), op.get('id'));
            v_props := v_o.to_clob;
            BEGIN
                INSERT INTO ${graph.nodes} (labels, props) VALUES (v_labels, v_props) RETURNING nid INTO v_nid;
                v_stamp := NULL;
                EXIT;
            EXCEPTION WHEN DUP_VAL_ON_INDEX THEN
                NULL; -- another writer made it at this moment: find theirs, and lock it
            END;
        END LOOP;
        v_found := v_stamp;
        IF v_expect IS NOT NULL AND (v_stamp IS NULL OR
                CASE WHEN NVL(op.get_boolean('whole'), FALSE) THEN v_stamp ELSE SUBSTR(v_stamp, 1, 16) END <> v_expect) THEN
            RAISE e_stale;
        END IF;

        v_o := JSON_OBJECT_T.parse(v_props);
        IF v_set IS NOT NULL THEN
            v_keys := v_set.get_keys;
            IF v_keys IS NOT NULL THEN
                FOR i IN 1 .. v_keys.COUNT LOOP
                    v_o.put(v_keys(i), v_set.get(v_keys(i)));
                END LOOP;
            END IF;
        END IF;
        v_array := op.get_array('unset');
        IF v_array IS NOT NULL THEN
            FOR i IN 0 .. v_array.get_size - 1 LOOP
                v_o.remove(v_array.get_string(i));
            END LOOP;
        END IF;
        -- The keys of a property bag that the object no longer holds.
        IF v_bags IS NOT NULL THEN
            FOR b IN 0 .. v_bags.get_size - 1 LOOP
                v_keep := members(TREAT(v_bags.get(b) AS JSON_OBJECT_T).get_array('keep'));
                v_keys := v_o.get_keys;
                IF v_keys IS NOT NULL THEN
                    FOR i IN 1 .. v_keys.COUNT LOOP
                        IF NOT v_keep.EXISTS(v_keys(i)) AND owns(TREAT(v_bags.get(b) AS JSON_OBJECT_T), v_keys(i)) THEN
                            v_o.remove(v_keys(i));
                        END IF;
                    END LOOP;
                END IF;
            END LOOP;
        END IF;
        -- The labels an open label field holds are recorded on the node, so it knows which are its own.
        IF v_owned IS NOT NULL THEN
            v_recorded := members(v_o.get_array(v_owned.get_string('prop')));
            IF NVL(v_owned.get_boolean('clear'), FALSE) THEN
                v_keep := members(v_owned.get_array('base'));
                v_label := v_recorded.FIRST;
                WHILE v_label IS NOT NULL LOOP
                    IF NOT v_add.EXISTS(v_label) AND NOT v_keep.EXISTS(v_label) THEN
                        v_drop(v_label) := TRUE;
                    END IF;
                    v_label := v_recorded.NEXT(v_label);
                END LOOP;
                IF v_add.COUNT = 0 THEN
                    v_o.remove(v_owned.get_string('prop'));
                ELSE
                    v_o.put(v_owned.get_string('prop'), op.get_array('add'));
                END IF;
            ELSIF v_add.COUNT > 0 THEN
                v_array := JSON_ARRAY_T();
                v_label := v_recorded.FIRST;
                WHILE v_label IS NOT NULL LOOP
                    IF NOT v_add.EXISTS(v_label) THEN
                        v_array.append(v_label);
                    END IF;
                    v_label := v_recorded.NEXT(v_label);
                END LOOP;
                FOR i IN 0 .. op.get_array('add').get_size - 1 LOOP
                    v_array.append(op.get_array('add').get_string(i));
                END LOOP;
                v_o.put(v_owned.get_string('prop'), v_array);
            END IF;
        END IF;

        v_have := members(JSON_ARRAY_T.parse(v_labels));
        v_label := v_add.FIRST;
        WHILE v_label IS NOT NULL LOOP
            IF NOT v_have.EXISTS(v_label) THEN
                v_have(v_label) := TRUE;
                v_relabel := TRUE;
            END IF;
            v_label := v_add.NEXT(v_label);
        END LOOP;
        v_label := v_drop.FIRST;
        WHILE v_label IS NOT NULL LOOP
            IF v_have.EXISTS(v_label) THEN
                v_have.DELETE(v_label);
                v_relabel := TRUE;
            END IF;
            v_label := v_drop.NEXT(v_label);
        END LOOP;
        IF v_relabel THEN
            v_array := JSON_ARRAY_T();
            v_label := v_have.FIRST;
            WHILE v_label IS NOT NULL LOOP
                v_array.append(v_label);
                v_label := v_have.NEXT(v_label);
            END LOOP;
            v_labels := v_array.to_string;
        END IF;

        -- A new first token only if the step changes the node, or the node has no stamp yet.
        v_new_props := v_o.to_clob;
        SELECT CASE WHEN JSON_EQUAL(v_props, v_new_props) THEN 1 ELSE 0 END INTO v_same FROM dual;
        IF v_stamp IS NULL OR v_same = 0 OR v_relabel THEN
            v_stamp := SUBSTR(v_fresh, 1, 16) || NVL(SUBSTR(v_stamp, 17), SUBSTR(v_fresh, 17));
            UPDATE ${graph.nodes} SET labels = v_labels, props = v_new_props, stamp = v_stamp WHERE nid = v_nid;
        END IF;

        v_nodes(op.get_number('r')) := v_nid;
        IF op.has('i') THEN
            v_h_index(v_h_index.COUNT) := op.get_string('i');
            v_h_found(v_h_found.COUNT) := v_found;
            v_h_node(v_h_node.COUNT) := v_nid;
        END IF;
        IF NVL(op.get_boolean('root'), FALSE) THEN
            v_root := v_nid;
            v_was := v_found;
            v_carried := op.get_string('carried');
        END IF;
    END;

    PROCEDURE find_node(op JSON_OBJECT_T) IS
        v_nid    NUMBER;
        v_stamp  VARCHAR2(33);
        v_labels VARCHAR2(4000);
        v_props  CLOB;
    BEGIN
        find(op.get_number('s'), op.get_string('k'), v_nid, v_stamp, v_labels, v_props);
        IF v_nid IS NULL THEN
            RAISE e_absent;
        END IF;
        v_nodes(op.get_number('r')) := v_nid;
    END;

    -- Joins two nodes. The relationship is as the step says only if every one of its type between them is.
    PROCEDURE link(op JSON_OBJECT_T) IS
        v_from   NUMBER := v_nodes(op.get_number('from'));
        v_to     NUMBER := v_nodes(op.get_number('to'));
        v_type   VARCHAR2(128) := op.get_string('type');
        v_dir    VARCHAR2(4) := op.get_string('dir');
        v_props  JSON_OBJECT_T := op.get_object('props');
        v_clear  JSON_ARRAY_T := op.get_array('clear');
        v_keys   JSON_KEY_LIST;
        v_x      JSON_OBJECT_T;
        v_text   CLOB;
        v_had    PLS_INTEGER := 0;
        v_same   PLS_INTEGER := 0;
        v_match  BOOLEAN;
    BEGIN
        IF v_props IS NULL THEN
            v_props := JSON_OBJECT_T();
        END IF;
        IF v_clear IS NULL THEN
            v_clear := JSON_ARRAY_T();
        END IF;
        v_keys := v_props.get_keys;
        IF NOT NVL(op.get_boolean('always'), FALSE) THEN
            FOR e IN (
                SELECT eid, JSON_SERIALIZE(props RETURNING CLOB) AS props FROM ${graph.edges}
                WHERE type = v_type
                  AND ((v_dir IN ('OUT', 'BOTH') AND src = v_from AND dst = v_to) OR (v_dir IN ('IN', 'BOTH') AND src = v_to AND dst = v_from))
            ) LOOP
                v_had := v_had + 1;
                v_x := JSON_OBJECT_T.parse(e.props);
                v_match := TRUE;
                IF v_keys IS NOT NULL THEN
                    FOR i IN 1 .. v_keys.COUNT LOOP
                        IF NOT v_x.has(v_keys(i)) OR v_x.get(v_keys(i)).to_string <> v_props.get(v_keys(i)).to_string THEN
                            v_match := FALSE;
                            v_x.put(v_keys(i), v_props.get(v_keys(i)));
                        END IF;
                    END LOOP;
                END IF;
                FOR i IN 0 .. v_clear.get_size - 1 LOOP
                    IF v_x.has(v_clear.get_string(i)) THEN
                        v_match := FALSE;
                        v_x.remove(v_clear.get_string(i));
                    END IF;
                END LOOP;
                IF v_match THEN
                    v_same := v_same + 1;
                ELSE
                    v_text := v_x.to_clob;
                    UPDATE ${graph.edges} SET props = v_text WHERE eid = e.eid;
                END IF;
            END LOOP;
        END IF;
        IF v_had = 0 THEN
            -- An undirected field is satisfied by a relationship either way, and makes one from the first node.
            v_text := v_props.to_clob;
            INSERT INTO ${graph.edges} (src, dst, type, props)
            VALUES (CASE WHEN v_dir = 'IN' THEN v_to ELSE v_from END, CASE WHEN v_dir = 'IN' THEN v_from ELSE v_to END, v_type, v_text);
        END IF;
        IF v_had = 0 OR v_same < v_had THEN
            relink(v_from);
            relink(v_to);
        END IF;
        v_count := v_count + GREATEST(v_had, 1);
    END;

    -- Removes a field's relationships to the targets whose ids the step names, or to every target but those.
    PROCEDURE unlink(op JSON_OBJECT_T) IS
        v_from    NUMBER := v_nodes(op.get_number('from'));
        v_dir     VARCHAR2(4) := op.get_string('dir');
        v_ids     t_set := members(op.get_array('ids'));
        v_held    t_set := members(op.get_array('held'));
        v_keep    BOOLEAN := NVL(op.get_boolean('keep'), FALSE);
        v_delete  BOOLEAN := NVL(op.get_boolean('delete'), FALSE);
        v_eids    t_ids;
        v_nids    t_ids;
        v_keys    t_keys;
        v_gone    t_ids := t_ids();
        v_removed t_counts;
        v_key_of  t_texts;
        v_target  VARCHAR2(40);
        v_refs    PLS_INTEGER;
        v_lock    NUMBER;
    BEGIN
        joined(op.get_number('s'), v_from, op.get_string('type'), v_dir, v_eids, v_nids, v_keys);
        FOR i IN 1 .. v_eids.COUNT LOOP
            IF v_keys(i) IS NOT NULL AND v_ids.EXISTS(v_keys(i)) <> v_keep THEN
                v_gone.EXTEND;
                v_gone(v_gone.COUNT) := v_eids(i);
                v_target := TO_CHAR(v_nids(i));
                v_removed(v_target) := CASE WHEN v_removed.EXISTS(v_target) THEN v_removed(v_target) ELSE 0 END + 1;
                v_key_of(v_nids(i)) := v_keys(i);
            END IF;
        END LOOP;
        IF v_gone.COUNT = 0 THEN
            RETURN;
        END IF;
        v_target := v_removed.FIRST;
        WHILE v_target IS NOT NULL LOOP
            SELECT nid INTO v_lock FROM ${graph.nodes} WHERE nid = TO_NUMBER(v_target) FOR UPDATE;
            -- What refers to a target is a relationship on the side this field reaches it from: counted before any is removed.
            SELECT COUNT(*) INTO v_refs FROM ${graph.edges}
            WHERE (v_dir IN ('OUT', 'BOTH') AND dst = v_lock) OR (v_dir IN ('IN', 'BOTH') AND src = v_lock);
            relink(v_lock);
            -- The root is never deleted, and nor is a node the object still holds in another field.
            IF v_delete AND v_lock <> v_from AND v_refs = v_removed(v_target) AND NOT v_held.EXISTS(v_key_of(v_lock)) THEN
                DELETE FROM ${graph.nodes} WHERE nid = v_lock;
            END IF;
            v_target := v_removed.NEXT(v_target);
        END LOOP;
        FORALL i IN 1 .. v_gone.COUNT
            DELETE FROM ${graph.edges} WHERE eid = v_gone(i);
        relink(v_from);
        v_count := v_count + v_gone.COUNT;
    END;

    -- Removes the relationships of a type from one node to another, or with `all` to any node in a direction.
    PROCEDURE cut(op JSON_OBJECT_T) IS
        v_from  NUMBER := v_nodes(op.get_number('from'));
        v_to    NUMBER := CASE WHEN op.has('to') THEN v_nodes(op.get_number('to')) END;
        v_type  VARCHAR2(128) := op.get_string('type');
        v_dir   VARCHAR2(4) := op.get_string('dir');
        v_eids  t_ids;
        v_ends  t_ids;
        v_lock  NUMBER;
    BEGIN
        SELECT eid, CASE WHEN src = v_from THEN dst ELSE src END BULK COLLECT INTO v_eids, v_ends FROM ${graph.edges}
        WHERE type = v_type
          AND ((v_dir IN ('OUT', 'BOTH') AND src = v_from AND (v_to IS NULL OR dst = v_to)) OR (v_dir IN ('IN', 'BOTH') AND dst = v_from AND (v_to IS NULL OR src = v_to)));
        IF v_eids.COUNT = 0 THEN
            RETURN;
        END IF;
        FOR i IN 1 .. v_ends.COUNT LOOP
            SELECT nid INTO v_lock FROM ${graph.nodes} WHERE nid = v_ends(i) FOR UPDATE;
            relink(v_lock);
        END LOOP;
        FORALL i IN 1 .. v_eids.COUNT
            DELETE FROM ${graph.edges} WHERE eid = v_eids(i);
        relink(v_from);
        v_count := v_count + v_eids.COUNT;
    END;
BEGIN
    BEGIN
        FOR i IN 0 .. v_plan.get_size - 1 LOOP
            v_op := TREAT(v_plan.get(i) AS JSON_OBJECT_T);
            CASE v_op.get_string('op')
                WHEN 'node' THEN save_node(v_op);
                WHEN 'ref' THEN find_node(v_op);
                WHEN 'link' THEN link(v_op);
                WHEN 'unlink' THEN unlink(v_op);
                WHEN 'cut' THEN cut(v_op);
            END CASE;
        END LOOP;
${if (stamps) STAMPS.replace(NODES, graph.nodes) else ""}
    EXCEPTION
        WHEN e_stale THEN v_result := NULL; -- raised before anything was written
        WHEN e_absent THEN v_count := 0; -- a node the plan refers to is not there: nothing was written
    END;
    OPEN v_rows FOR ${if (stamps) "SELECT v_result FROM dual WHERE v_result IS NOT NULL" else "SELECT v_count FROM dual"};
    DBMS_SQL.RETURN_RESULT(v_rows);
END;
""".trim()

    private fun identified(alias: String, shape: NodeShape): String =
        listOfNotNull("JSON_VALUE($alias.props, ${path(shape.key)}) = p_id", hasLabels(alias, shape.labels)).joinToString(" AND ")

    private fun finder(index: Int, shape: NodeShape): String = """
            WHEN $index THEN
                FOR c IN (
                    SELECT n.nid, n.stamp, JSON_SERIALIZE(n.labels) AS labels, JSON_SERIALIZE(n.props RETURNING CLOB) AS props
                    FROM ${graph.nodes} n WHERE ${identified("n", shape)} FOR UPDATE
                ) LOOP
                    o_nid := c.nid; o_stamp := c.stamp; o_labels := c.labels; o_props := c.props;
                    EXIT;
                END LOOP;
    """.trimEnd().trimStart('\n')

    private fun joiner(index: Int, shape: NodeShape): String = """
            WHEN $index THEN
                SELECT e.eid, t.nid, JSON_VALUE(t.props, ${path(shape.key)}) BULK COLLECT INTO o_eids, o_nids, o_keys
                FROM ${graph.edges} e JOIN ${graph.nodes} t
                  ON (p_dir IN ('OUT', 'BOTH') AND e.src = p_from AND t.nid = e.dst) OR (p_dir IN ('IN', 'BOTH') AND e.dst = p_from AND t.nid = e.src)
                WHERE e.type = p_type${hasLabels("t", shape.labels)?.let { " AND $it" }.orEmpty()};
    """.trimEnd().trimStart('\n')

    companion object {
        const val PLAN = "_plan"
        const val MARK = "_mark"

        /**
         * The stamps a save hands back, read when every step has run: the root's, then `index/found=stamp`
         * for each node whose stamp is handed back, `found` being the stamp it had before the save. The
         * root's keeps the object's own token wherever the node had another when the save began.
         */
        private val STAMPS = """
        SELECT stamp INTO v_stamp FROM #NODES# WHERE nid = v_root;
        IF v_carried IS NOT NULL THEN
            v_stamp := CASE WHEN SUBSTR(v_was, 1, 16) = SUBSTR(v_carried, 1, 16) THEN SUBSTR(v_stamp, 1, 16) ELSE SUBSTR(v_carried, 1, 16) END || ':' ||
                CASE WHEN SUBSTR(v_was, 18) = SUBSTR(v_carried, 18) THEN SUBSTR(v_stamp, 18) ELSE SUBSTR(v_carried, 18) END;
        END IF;
        v_result := v_stamp;
        FOR i IN 0 .. v_h_node.COUNT - 1 LOOP
            SELECT stamp INTO v_stamp FROM #NODES# WHERE nid = v_h_node(i);
            v_result := v_result || ',' || v_h_index(i) || '/' || v_h_found(i) || '=' || v_stamp;
        END LOOP;"""

        private const val NODES = "#NODES#"
    }
}
