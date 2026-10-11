package org.drivine.backend.oracle

import org.drivine.backend.oracle.OracleSql.hasLabels
import org.drivine.backend.oracle.OracleSql.path
import org.drivine.model.Stamps

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
 * - `link` joins two nodes; `unlink` removes a field's relationships to some of its targets; `cut`
 *   removes the relationships of a type to one node, or with no node named, to every node in a
 *   direction. Each gives the nodes at both ends a new second token when it changes what joins them.
 *
 * A node is locked before it is read, so what a step compares is what the last writer committed; a
 * relationship is written only by a step that holds the lock of the node at each end. A lock is always
 * taken by the node's key, never through an index over its properties: see `find`. A node that is not
 * there is made, and nothing stops two writers each making it at the same moment unless a uniqueness
 * constraint on its id does, as nothing stops two `MERGE`s on a graph engine without one.
 *
 * The text is one PL/SQL block, written here one procedure at a time, in the order they appear in it.
 *
 * @param returns what the block gives back when its steps have run
 */
internal class OracleWriteBlock(private val graph: OracleGraph, private val shapes: List<NodeShape>, private val returns: Returns) {

    /**
     * What a block gives back when its steps have run.
     *
     * @param gathering the statements that leave the result in `v_result`, run after every step; `#NODES#` stands for the node table
     * @param row the query the block returns the rows of
     */
    enum class Returns(internal val gathering: String, internal val row: String) {
        /**
         * One row: the stamps a save hands back, the root's first, then `index/found=stamp` for each node
         * whose stamp is handed back, `found` being the stamp it had before the save. The root's keeps
         * the object's own token wherever the node had another when the save began: see
         * `Stamps.handedBack`. A node the save made has no stamp to differ from, so an object that
         * carried a stamp without a relationship token is handed the new one. No row when a checked root is stale.
         */
        STAMPS(
            """
        SELECT stamp INTO v_stamp FROM #NODES# WHERE nid = v_root;
        IF v_carried IS NULL THEN
            -- An object that carried no stamp is handed the whole stamp only of a node its save made.
            IF NOT v_root_made THEN
                v_stamp := SUBSTR(v_stamp, 1, 17);
            END IF;
        ELSIF v_was = v_carried THEN
            NULL; -- found exactly as loaded: the node's stamp is handed back whole
        ELSE
            v_stamp := CASE WHEN SUBSTR(v_was, 1, 16) = SUBSTR(v_carried, 1, 16) THEN SUBSTR(v_stamp, 1, 16) ELSE SUBSTR(v_carried, 1, 16) END || ':' ||
                CASE WHEN SUBSTR(v_was, 18) = SUBSTR(v_carried, 18) OR (v_root_made AND SUBSTR(v_carried, 18) IS NULL) THEN SUBSTR(v_stamp, 18) ELSE SUBSTR(v_carried, 18) END;
        END IF;
        v_result := v_stamp;
        FOR i IN 0 .. v_h_node.COUNT - 1 LOOP
            SELECT stamp INTO v_stamp FROM #NODES# WHERE nid = v_h_node(i);
            v_result := v_result || ',' || v_h_index(i) || '/' || v_h_found(i) || '=' || v_stamp;
        END LOOP;""",
            "SELECT v_result FROM dual WHERE v_result IS NOT NULL",
        ),

        /** A row for each node whose stamp is handed back, `index/found=stamp`: what a batch of fragments returns. */
        EACH_STAMP(
            """
        v_each := JSON_ARRAY_T();
        FOR i IN 0 .. v_h_node.COUNT - 1 LOOP
            SELECT stamp INTO v_stamp FROM #NODES# WHERE nid = v_h_node(i);
            v_each.append(v_h_index(i) || '/' || v_h_found(i) || '=' || v_stamp);
        END LOOP;
        v_result := v_each.to_clob;""",
            "SELECT v FROM JSON_TABLE(v_result, '$[*]' COLUMNS (v VARCHAR2(400) PATH '$'))",
        ),

        /** One row: the number of relationships the steps counted. */
        COUNT("", "SELECT v_count FROM dual"),
    }

    /** The statement. Its parameters are [PLAN] and [MARK]. */
    fun text(): String =
        listOf(declarations(), finding(), joining(), relinking(), spanning(), members(), owning(), savingNode(), findingNode(), linking(), unlinking(), cutting())
            .joinToString("\n\n") + "\n" + main()

    /** The block's types, variables and exceptions. */
    private fun declarations(): String = """
DECLARE
    TYPE t_numbers IS TABLE OF NUMBER INDEX BY PLS_INTEGER;
    TYPE t_texts IS TABLE OF VARCHAR2(4000) INDEX BY PLS_INTEGER;
    TYPE t_set IS TABLE OF BOOLEAN INDEX BY VARCHAR2(4000);
    TYPE t_counts IS TABLE OF PLS_INTEGER INDEX BY VARCHAR2(40);
    TYPE t_ids IS TABLE OF NUMBER;
    TYPE t_keys IS TABLE OF VARCHAR2(4000);
    TYPE t_docs IS TABLE OF CLOB;
    TYPE t_flags IS TABLE OF BOOLEAN INDEX BY PLS_INTEGER;
    v_plan    JSON_ARRAY_T := JSON_ARRAY_T.parse(${'$'}$PLAN);
    v_mark    VARCHAR2(33) := ${'$'}$MARK;
    v_op      JSON_OBJECT_T;
    v_nodes   t_numbers;
    v_h_index t_texts;
    v_h_found t_texts;
    v_h_node  t_numbers;
    v_relinks t_flags;
    v_root    NUMBER;
    v_root_made BOOLEAN := FALSE;
    v_was     VARCHAR2(33);
    v_carried VARCHAR2(33);
    v_stamp   VARCHAR2(33);
    v_count   NUMBER := 0;
    v_result  CLOB;
    v_each    JSON_ARRAY_T;
    v_rows    SYS_REFCURSOR;
    e_stale   EXCEPTION;
    e_absent  EXCEPTION;
""".trim('\n')

    /** `find`: a node of a shape by its id, locked. */
    private fun finding(): String = """
    -- Finds the node of a shape by its id, and locks it. A null o_nid says there is none.
    --
    -- The node is found without a lock, then locked by its key and looked at again. To lock it as it
    -- is found would wait for another writer's lock while holding the page of the index it was found
    -- through, and that writer, whose update keeps the same index, would wait for the page: each for
    -- the other, and the database sees no deadlock in a held page.
    PROCEDURE find(p_shape NUMBER, p_id VARCHAR2, o_nid OUT NUMBER, o_stamp OUT VARCHAR2, o_labels OUT VARCHAR2, o_props OUT CLOB) IS
        v_nid   NUMBER;
        v_still NUMBER;
    BEGIN
        LOOP
            CASE p_shape
${shapes.withIndex().joinToString("\n") { (index, shape) -> finder(index, shape) }}
            END CASE;
            IF v_nid IS NULL THEN
                o_nid := NULL;
                RETURN;
            END IF;
            BEGIN
                SELECT n.nid, n.stamp, JSON_SERIALIZE(n.labels), JSON_SERIALIZE(n.props RETURNING CLOB)
                INTO o_nid, o_stamp, o_labels, o_props
                FROM ${graph.nodes} n WHERE n.nid = v_nid FOR UPDATE;
                -- Locked: is it still the node that was looked for? Another writer may have changed it meanwhile.
                CASE p_shape
${shapes.withIndex().joinToString("\n") { (index, shape) -> verifier(index, shape) }}
                END CASE;
                IF v_still > 0 THEN
                    RETURN;
                END IF;
            EXCEPTION WHEN NO_DATA_FOUND THEN
                NULL; -- deleted between the two: look again
            END;
        END LOOP;
    END;
""".trim('\n')

    /** `joined`: the targets of a shape a node is joined to. */
    private fun joining(): String = """
    -- The targets of a shape that a node is joined to by relationships of a type, with each relationship.
    -- Each direction is a statement of its own, so each reads the index on its end of a relationship.
    PROCEDURE joined(p_shape NUMBER, p_from NUMBER, p_type VARCHAR2, p_dir VARCHAR2, o_eids OUT t_ids, o_nids OUT t_ids, o_keys OUT t_keys) IS
        v_eids t_ids := t_ids();
        v_nids t_ids := t_ids();
        v_keys t_keys := t_keys();
    BEGIN
        o_eids := t_ids();
        o_nids := t_ids();
        o_keys := t_keys();
        IF p_dir IN ('OUT', 'BOTH') THEN
            CASE p_shape
${shapes.withIndex().joinToString("\n") { (index, shape) -> joiner(index, shape, outgoing = true) }}
            END CASE;
            o_eids := v_eids; o_nids := v_nids; o_keys := v_keys;
        END IF;
        IF p_dir IN ('IN', 'BOTH') THEN
            CASE p_shape
${shapes.withIndex().joinToString("\n") { (index, shape) -> joiner(index, shape, outgoing = false) }}
            END CASE;
            o_eids := o_eids MULTISET UNION ALL v_eids; o_nids := o_nids MULTISET UNION ALL v_nids; o_keys := o_keys MULTISET UNION ALL v_keys;
        END IF;
    END;
""".trim('\n')

    /** `relink` and `relink_marked`: the nodes whose relationships changed get a new second token. */
    private fun relinking(): String = """
    -- Marks a node for a new second token: a relationship of it was made, removed or changed.
    -- It is given once, when every step has run, however many of the node's relationships changed.
    PROCEDURE relink(p_nid NUMBER) IS
    BEGIN
        v_relinks(p_nid) := TRUE;
    END;

    PROCEDURE relink_marked IS
        v_nid PLS_INTEGER := v_relinks.FIRST;
    BEGIN
        WHILE v_nid IS NOT NULL LOOP
            -- A stamp that is not two tokens, which no save leaves, is replaced whole.
            UPDATE ${graph.nodes} SET stamp = NVL(SUBSTR(CASE WHEN LENGTH(stamp) = 33 AND SUBSTR(stamp, 17, 1) = ':' THEN stamp END, 1, 17), SUBSTR(v_mark, 1, 17)) || SUBSTR(v_mark, 18) WHERE nid = v_nid;
            v_nid := v_relinks.NEXT(v_nid);
        END LOOP;
    END;
""".trim('\n')

    /** `spanning`: the relationships of a type between two nodes. */
    private fun spanning(): String = """
    -- The relationships of a type from one node to another.
    PROCEDURE spanning(p_src NUMBER, p_dst NUMBER, p_type VARCHAR2, o_eids IN OUT NOCOPY t_ids, o_props IN OUT NOCOPY t_docs) IS
        v_eids  t_ids;
        v_props t_docs;
    BEGIN
        SELECT eid, JSON_SERIALIZE(props RETURNING CLOB) BULK COLLECT INTO v_eids, v_props
        FROM ${graph.edges} WHERE src = p_src AND dst = p_dst AND type = p_type;
        o_eids := o_eids MULTISET UNION ALL v_eids;
        o_props := o_props MULTISET UNION ALL v_props;
    END;
""".trim('\n')

    /** `members`: a JSON array as a set. */
    private fun members(): String = """
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
""".trim('\n')

    /** `owns`: whether a property bag owns a stored key. */
    private fun owning(): String = """
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
""".trim('\n')

    /** `save_node`: the `node` step. */
    private fun savingNode(): String = """
    PROCEDURE save_node(op JSON_OBJECT_T) IS
        v_nid       NUMBER;
        v_stamp     VARCHAR2(33);
        v_found     VARCHAR2(33);
        v_two       VARCHAR2(33);
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
        v_add       t_set;
        v_drop      t_set;
        v_recorded  t_set;
        v_label     VARCHAR2(4000);
        v_made      BOOLEAN;
        v_dup       PLS_INTEGER := 0;
        v_relabel   BOOLEAN;
        v_same      NUMBER;
    BEGIN
        LOOP
            find(op.get_number('s'), op.get_string('k'), v_nid, v_stamp, v_labels, v_props);
            v_made := v_nid IS NULL;
            IF v_made THEN
                -- A checked save does not make the node again: it is gone, and the object is stale.
                -- Nor does a step that makes no node: the block gives no row, and has written nothing.
                IF v_expect IS NOT NULL OR NOT NVL(op.get_boolean('create'), TRUE) THEN
                    RAISE e_stale;
                END IF;
                v_labels := op.get_array('labels').to_string;
                v_o := JSON_OBJECT_T();
                v_o.put(op.get_string('key'), op.get('id'));
                v_stamp := NULL;
            ELSE
                v_o := JSON_OBJECT_T.parse(v_props);
            END IF;
            v_found := v_stamp;
            IF v_expect IS NOT NULL AND (v_stamp IS NULL OR
                    CASE WHEN NVL(op.get_boolean('whole'), FALSE) THEN v_stamp ELSE SUBSTR(v_stamp, 1, 16) END <> v_expect) THEN
                RAISE e_stale;
            END IF;

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
            v_add := members(op.get_array('add'));
            v_drop := members(op.get_array('drop'));
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

            v_relabel := FALSE;
            IF v_add.COUNT > 0 OR v_drop.COUNT > 0 THEN
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
            END IF;

            v_new_props := v_o.to_clob;
            IF v_made THEN
                -- A node that is made is written once, whole, with a stamp of its own.
                v_stamp := v_fresh;
                BEGIN
                    INSERT INTO ${graph.nodes} (labels, props, stamp) VALUES (v_labels, v_new_props, v_stamp) RETURNING nid INTO v_nid;
                    EXIT;
                EXCEPTION WHEN DUP_VAL_ON_INDEX THEN
                    -- A uniqueness constraint refused the node. Once, it may be that another writer made the
                    -- node at this moment: find theirs, lock it, and write to that. Found again or not, the
                    -- constraint is on something other than this node's key, and refuses this save.
                    v_dup := v_dup + 1;
                    IF v_dup > 1 THEN RAISE; END IF;
                END;
            ELSE
                -- A new first token only if the step changes the node, or the node has no stamp yet.
                SELECT CASE WHEN JSON_EQUAL(v_props, v_new_props) THEN 1 ELSE 0 END INTO v_same FROM dual;
                -- A stamp that is not two tokens, which no save leaves, counts as none.
                v_two := CASE WHEN LENGTH(v_stamp) = 33 AND SUBSTR(v_stamp, 17, 1) = ':' THEN v_stamp END;
                IF v_two IS NULL OR v_same = 0 OR v_relabel THEN
                    v_stamp := SUBSTR(v_fresh, 1, 16) || NVL(SUBSTR(v_two, 17), SUBSTR(v_fresh, 17));
                    -- Each column is written only when it changes: the labels and the properties are each indexed.
                    IF v_relabel THEN
                        UPDATE ${graph.nodes} SET labels = v_labels WHERE nid = v_nid;
                    END IF;
                    IF v_same = 0 THEN
                        UPDATE ${graph.nodes} SET props = v_new_props, stamp = v_stamp WHERE nid = v_nid;
                    ELSE
                        UPDATE ${graph.nodes} SET stamp = v_stamp WHERE nid = v_nid;
                    END IF;
                END IF;
                EXIT;
            END IF;
        END LOOP;

        v_nodes(op.get_number('r')) := v_nid;
        IF op.has('i') THEN
            v_h_index(v_h_index.COUNT) := op.get_string('i');
            -- Nothing for a node the step made, and a mark for one that was there without a stamp.
            v_h_found(v_h_found.COUNT) := CASE WHEN v_made THEN NULL ELSE NVL(v_found, '$NEVER_STAMPED') END;
            v_h_node(v_h_node.COUNT) := v_nid;
        END IF;
        IF NVL(op.get_boolean('root'), FALSE) THEN
            v_root := v_nid;
            v_root_made := v_made;
            v_was := v_found;
            v_carried := op.get_string('carried');
        END IF;
    END;
""".trim('\n')

    /** `find_node`: the `ref` step. */
    private fun findingNode(): String = """
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
""".trim('\n')

    /** `link`: the `link` step. */
    private fun linking(): String = """
    -- Joins two nodes. The relationship is as the step says only if every one of its type between them is.
    PROCEDURE link(op JSON_OBJECT_T) IS
        v_from   NUMBER := v_nodes(op.get_number('from'));
        v_to     NUMBER := v_nodes(op.get_number('to'));
        v_type   VARCHAR2(128) := op.get_string('type');
        v_dir    VARCHAR2(4) := op.get_string('dir');
        v_props  JSON_OBJECT_T := op.get_object('props');
        v_clear  JSON_ARRAY_T := op.get_array('clear');
        v_keys   JSON_KEY_LIST;
        v_eids   t_ids := t_ids();
        v_docs   t_docs := t_docs();
        v_x      JSON_OBJECT_T;
        v_text   CLOB;
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
            IF v_dir IN ('OUT', 'BOTH') THEN
                spanning(v_from, v_to, v_type, v_eids, v_docs);
            END IF;
            -- A node's relationship to itself is one relationship, whichever way it is read.
            IF v_dir = 'IN' OR (v_dir = 'BOTH' AND v_from <> v_to) THEN
                spanning(v_to, v_from, v_type, v_eids, v_docs);
            END IF;
            FOR e IN 1 .. v_eids.COUNT LOOP
                v_match := TRUE;
                IF (v_keys IS NOT NULL AND v_keys.COUNT > 0) OR v_clear.get_size > 0 THEN
                    v_x := JSON_OBJECT_T.parse(v_docs(e));
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
                END IF;
                IF v_match THEN
                    v_same := v_same + 1;
                ELSE
                    v_text := v_x.to_clob;
                    UPDATE ${graph.edges} SET props = v_text WHERE eid = v_eids(e);
                END IF;
            END LOOP;
        END IF;
        IF v_eids.COUNT = 0 THEN
            -- An undirected field is satisfied by a relationship either way, and makes one from the first node.
            v_text := v_props.to_clob;
            INSERT INTO ${graph.edges} (src, dst, type, props)
            VALUES (CASE WHEN v_dir = 'IN' THEN v_to ELSE v_from END, CASE WHEN v_dir = 'IN' THEN v_from ELSE v_to END, v_type, v_text);
        END IF;
        IF v_eids.COUNT = 0 OR v_same < v_eids.COUNT THEN
            relink(v_from);
            relink(v_to);
        END IF;
        v_count := v_count + GREATEST(v_eids.COUNT, 1);
    END;
""".trim('\n')

    /** `unlink`: the `unlink` step. */
    private fun unlinking(): String = """
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
            -- A target gone since the relationships were read has nothing left to remove or count.
            BEGIN
                SELECT nid INTO v_lock FROM ${graph.nodes} WHERE nid = TO_NUMBER(v_target) FOR UPDATE;
            EXCEPTION WHEN NO_DATA_FOUND THEN
                v_target := v_removed.NEXT(v_target);
                CONTINUE;
            END;
            -- What refers to a target is a relationship on the side this field reaches it from: counted before any is removed.
            v_refs := 0;
            IF v_delete THEN
                IF v_dir IN ('OUT', 'BOTH') THEN
                    SELECT COUNT(*) INTO v_refs FROM ${graph.edges} WHERE dst = v_lock;
                END IF;
                IF v_dir IN ('IN', 'BOTH') THEN
                    -- A relationship from the node to itself is counted once.
                    SELECT v_refs + COUNT(*) INTO v_refs FROM ${graph.edges} WHERE src = v_lock AND (v_dir = 'IN' OR dst <> v_lock);
                END IF;
            END IF;
            -- The root is never deleted, and nor is a node the object still holds in another field.
            IF v_delete AND v_lock <> v_from AND v_refs = v_removed(v_target) AND NOT v_held.EXISTS(v_key_of(v_lock)) THEN
                v_relinks.DELETE(v_lock);
                DELETE FROM ${graph.nodes} WHERE nid = v_lock;
            ELSE
                relink(v_lock);
            END IF;
            v_target := v_removed.NEXT(v_target);
        END LOOP;
        FORALL i IN 1 .. v_gone.COUNT
            DELETE FROM ${graph.edges} WHERE eid = v_gone(i);
        relink(v_from);
        v_count := v_count + v_gone.COUNT;
    END;
""".trim('\n')

    /** `cut`: the `cut` step. */
    private fun cutting(): String = """
    -- Removes the relationships of a type from one node to another, or with no other node named to any in a direction.
    PROCEDURE cut(op JSON_OBJECT_T) IS
        v_from  NUMBER := v_nodes(op.get_number('from'));
        v_to    NUMBER := CASE WHEN op.has('to') THEN v_nodes(op.get_number('to')) END;
        v_type  VARCHAR2(128) := op.get_string('type');
        v_dir   VARCHAR2(4) := op.get_string('dir');
        v_eids  t_ids := t_ids();
        v_ends  t_ids := t_ids();
        v_more  t_ids;
        v_far   t_ids;
        v_lock  NUMBER;
    BEGIN
        IF v_dir IN ('OUT', 'BOTH') THEN
            SELECT eid, dst BULK COLLECT INTO v_eids, v_ends FROM ${graph.edges}
            WHERE src = v_from AND type = v_type AND (v_to IS NULL OR dst = v_to);
        END IF;
        IF v_dir IN ('IN', 'BOTH') THEN
            SELECT eid, src BULK COLLECT INTO v_more, v_far FROM ${graph.edges}
            WHERE dst = v_from AND type = v_type AND (v_to IS NULL OR src = v_to) AND (v_dir = 'IN' OR src <> v_from);
            v_eids := v_eids MULTISET UNION ALL v_more;
            v_ends := v_ends MULTISET UNION ALL v_far;
        END IF;
        IF v_eids.COUNT = 0 THEN
            RETURN;
        END IF;
        FOR i IN 1 .. v_ends.COUNT LOOP
            -- An end gone since the relationships were read took them with it, and has no token to change.
            BEGIN
                SELECT nid INTO v_lock FROM ${graph.nodes} WHERE nid = v_ends(i) FOR UPDATE;
                relink(v_lock);
            EXCEPTION WHEN NO_DATA_FOUND THEN
                NULL;
            END;
        END LOOP;
        FORALL i IN 1 .. v_eids.COUNT
            DELETE FROM ${graph.edges} WHERE eid = v_eids(i);
        relink(v_from);
        v_count := v_count + v_eids.COUNT;
    END;
""".trim('\n')

    /** The block itself: each step in turn, then what [returns] asks for. */
    private fun main(): String = """
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
        relink_marked;
${returns.gathering.replace(NODES, graph.nodes)}
    EXCEPTION
        WHEN e_stale THEN v_result := NULL; -- raised before anything was written
        WHEN e_absent THEN v_count := 0; -- a node the plan refers to is not there: nothing was written
    END;
    OPEN v_rows FOR ${returns.row};
    DBMS_SQL.RETURN_RESULT(v_rows);
END;
""".trim('\n')

    private fun identified(alias: String, shape: NodeShape): String =
        listOfNotNull("JSON_VALUE($alias.props, ${path(shape.key)}) = p_id", hasLabels(alias, shape.labels)).joinToString(" AND ")

    private fun finder(index: Int, shape: NodeShape): String = """
                WHEN $index THEN
                    SELECT MIN(n.nid) INTO v_nid FROM ${graph.nodes} n WHERE ${identified("n", shape)};
    """.trimEnd().trimStart('\n')

    private fun verifier(index: Int, shape: NodeShape): String = """
                    WHEN $index THEN
                        SELECT COUNT(*) INTO v_still FROM ${graph.nodes} n WHERE n.nid = v_nid AND ${identified("n", shape)};
    """.trimEnd().trimStart('\n')

    private fun joiner(index: Int, shape: NodeShape, outgoing: Boolean): String = """
                WHEN $index THEN
                    SELECT e.eid, t.nid, JSON_VALUE(t.props, ${path(shape.key)}) BULK COLLECT INTO v_eids, v_nids, v_keys
                    FROM ${graph.edges} e JOIN ${graph.nodes} t ON t.nid = e.${if (outgoing) "dst" else "src"}
                    WHERE e.${if (outgoing) "src" else "dst"} = p_from AND e.type = p_type${hasLabels("t", shape.labels)?.let { " AND $it" }.orEmpty()}${if (outgoing) "" else " AND (p_dir = 'IN' OR e.src <> p_from)"};
    """.trimEnd().trimStart('\n')

    companion object {
        const val PLAN = "_plan"
        const val MARK = "_mark"
        private const val NEVER_STAMPED = Stamps.NEVER_STAMPED

        /** What stands for the node table in a [Returns] text. */
        private const val NODES = "#NODES#"
    }
}
