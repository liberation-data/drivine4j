package org.drivine.backend.postgres

import org.drivine.model.Stamps
import org.drivine.query.PatchOutcomes

/**
 * The functions a graph kept by Postgres is written through, which [PostgresGraph] makes with its
 * tables. A save, `relate`, `unrelate` or `patch` is a plan, a JSON array of steps (see `WritePlan`),
 * and `<graph>_write` carries the steps out in turn, as the PL/SQL block does on Oracle: the plan is the
 * same, and so is what each step does. Postgres's own block, `DO`, returns no rows, so the interpreter
 * is a function the store keeps, and its text is the same for every plan.
 *
 * A call is one statement, run in a block of its own: a plan that fails, or that finds its root stale,
 * is rolled back to where it began, so a save is applied whole or not at all, with or without a
 * transaction around it.
 *
 * - `<graph>_find(labels, key, id)` finds the node of a shape by its id and locks it. The node is found
 *   without a lock, then locked by its key and looked at again: another writer may have changed it in
 *   between. The query is written for the shape it is asked, so it is planned as a query over that
 *   label and property, which a labelled index answers.
 * - `<graph>_owns(bag, key)` says whether a property bag owns a stored key.
 * - `<graph>_write(plan, shapes, mark, returns, patched_if)` carries out the plan. `shapes` are the
 *   shapes its steps name by number, each `{labels, key}`; `mark` is the stamp new relationship tokens
 *   are taken from; `returns` is what to give back (`STAMPS`, `EACH_STAMP` or `COUNT`); `patched_if`,
 *   when it is given, is the query a `patch` step asks whether to write to the node it found: it counts
 *   the node whose `nid` is its parameter `$1`, if the node meets a predicate.
 *
 * A stale root (the node is gone, or carries another stamp, or is there when the save may only make it)
 * is raised as [STALE] and gives no row; a node a `ref` step needs that is not there is raised as
 * [ABSENT] and counts nothing. Either way nothing was written.
 */
internal class PostgresWriteFunctions(private val graph: PostgresGraph) {

    private val nodes = graph.nodes
    private val edges = graph.edges
    private val find = "${graph.name}_find"
    private val owns = "${graph.name}_owns"

    /** The function that carries out a plan. */
    val write = "${graph.name}_write"

    /** The statements that make the functions, each safe to run again. */
    val ddl: List<String> get() = listOf(finding(), owning(), writing())

    private fun finding(): String = """
CREATE OR REPLACE FUNCTION $find(p_labels jsonb, p_key text, p_id text) RETURNS $nodes AS ${'$'}fn${'$'}
DECLARE
    v_nid bigint;
    v_row $nodes;
BEGIN
    LOOP
        EXECUTE format('SELECT min(n.nid) FROM $nodes n WHERE n.props ->> %L = ${'$'}1 AND n.labels @> %L::jsonb', p_key, p_labels)
            INTO v_nid USING p_id;
        IF v_nid IS NULL THEN
            RETURN NULL;
        END IF;
        SELECT * INTO v_row FROM $nodes n WHERE n.nid = v_nid FOR UPDATE;
        -- Locked: is it still the node that was looked for? Another writer may have changed it meanwhile.
        IF FOUND AND v_row.props ->> p_key = p_id AND v_row.labels @> p_labels THEN
            RETURN v_row;
        END IF;
    END LOOP;
END
${'$'}fn${'$'} LANGUAGE plpgsql
""".trim()

    private fun owning(): String = """
CREATE OR REPLACE FUNCTION $owns(p_bag jsonb, p_key text) RETURNS boolean AS ${'$'}fn${'$'}
DECLARE
    v_prefix   text := p_bag ->> 'prefix';
    v_reserved text;
BEGIN
    -- A bag with a prefix owns the keys that begin with it; a flat bag, each key that is no one else's.
    IF NOT COALESCE((p_bag ->> 'flat')::boolean, false) THEN
        RETURN length(p_key) > COALESCE(length(v_prefix), 0) AND (v_prefix IS NULL OR left(p_key, length(v_prefix)) = v_prefix);
    END IF;
    IF COALESCE(p_bag -> 'reservedKeys', '[]'::jsonb) @> to_jsonb(p_key) THEN
        RETURN false;
    END IF;
    FOR v_reserved IN SELECT jsonb_array_elements_text(COALESCE(p_bag -> 'reservedPrefixes', '[]'::jsonb)) LOOP
        IF left(p_key, length(v_reserved)) = v_reserved THEN
            RETURN false;
        END IF;
    END LOOP;
    RETURN true;
END
${'$'}fn${'$'} LANGUAGE plpgsql IMMUTABLE
""".trim()

    private fun writing(): String = """
CREATE OR REPLACE FUNCTION $write(p_plan jsonb, p_shapes jsonb, p_mark text, p_returns text, p_patched_if text) RETURNS SETOF text AS ${'$'}fn${'$'}
DECLARE
    v_op        jsonb;
    v_shape     jsonb;
    v_nodes     bigint[] := '{}';
    v_h_index   text[] := '{}';
    v_h_found   text[] := '{}';
    v_h_node    bigint[] := '{}';
    v_relinks   bigint[] := '{}';
    v_root      bigint;
    v_root_made boolean := false;
    v_was       text;
    v_carried   text;
    v_stamp     text;
    v_count     bigint := 0;
    v_result    text;
    v_each      text[] := '{}';
    v_stale     boolean := false;
    v_i         int;
    -- A node step's.
    v_row       $nodes;
    v_nid       bigint;
    v_found     text;
    v_two       text;
    v_labels    jsonb;
    v_o         jsonb;
    v_expect    text;
    v_fresh     text;
    v_add       jsonb;
    v_drop      jsonb;
    v_made      boolean;
    v_tries     int;
    v_relabel   boolean;
    v_bag       jsonb;
    v_key       text;
    v_owned     jsonb;
    v_recorded  jsonb;
    v_now       jsonb;
    v_same      boolean;
    v_met       bigint;
    -- A relationship step's.
    v_from      bigint;
    v_to        bigint;
    v_type      text;
    v_dir       text;
    v_props     jsonb;
    v_clear     jsonb;
    v_e         record;
    v_x         jsonb;
    v_seen      int;
    v_unchanged int;
    v_ids       jsonb;
    v_held      jsonb;
    v_keep      boolean;
    v_delete    boolean;
    v_gone      bigint[];
    v_targets   bigint[];
    v_keys      text[];
    v_target    bigint;
    v_refs      bigint;
    v_removed   bigint;
BEGIN
    BEGIN
        FOR v_op IN SELECT value FROM jsonb_array_elements(p_plan) LOOP
            CASE v_op ->> 'op'
            WHEN 'node' THEN
                -- Saves a node: found and locked, or made. What the step writes is worked out over the node as
                -- it was found, and the node is written only where that differs from what it holds.
                v_shape := p_shapes -> ((v_op ->> 's')::int);
                v_expect := v_op ->> 'expect';
                v_fresh := v_op ->> 'fresh';
                v_tries := 0;
                LOOP
                    v_row := $find(v_shape -> 'labels', v_shape ->> 'key', v_op ->> 'k');
                    v_made := v_row.nid IS NULL;
                    IF v_made THEN
                        -- A checked save does not make the node again: it is gone, and the object is stale.
                        -- Nor does a step that makes no node.
                        IF v_expect IS NOT NULL OR NOT COALESCE((v_op ->> 'create')::boolean, true) THEN
                            RAISE EXCEPTION 'stale' USING ERRCODE = '$STALE';
                        END IF;
                        v_labels := v_op -> 'labels';
                        v_o := jsonb_build_object(v_op ->> 'key', v_op -> 'id');
                        v_stamp := NULL;
                    ELSIF COALESCE((v_op ->> 'absent')::boolean, false) THEN
                        -- A step that only makes the node writes nothing to one that is there.
                        RAISE EXCEPTION 'stale' USING ERRCODE = '$STALE';
                    ELSE
                        v_labels := v_row.labels;
                        v_o := v_row.props;
                        v_stamp := v_row.stamp;
                    END IF;
                    v_found := v_stamp;
                    IF v_expect IS NOT NULL AND (v_stamp IS NULL OR
                            CASE WHEN COALESCE((v_op ->> 'whole')::boolean, false) THEN v_stamp ELSE substr(v_stamp, 1, 16) END <> v_expect) THEN
                        RAISE EXCEPTION 'stale' USING ERRCODE = '$STALE';
                    END IF;

                    -- The properties the step sets, and without those it unsets.
                    v_o := (v_o || COALESCE(v_op -> 'set', '{}'::jsonb)) - ARRAY(SELECT jsonb_array_elements_text(COALESCE(v_op -> 'unset', '[]'::jsonb)));
                    -- The keys of each property bag the object no longer holds.
                    FOR v_bag IN SELECT value FROM jsonb_array_elements(COALESCE(v_op -> 'bags', '[]'::jsonb)) LOOP
                        FOR v_key IN SELECT jsonb_object_keys(v_o) LOOP
                            IF NOT COALESCE(v_bag -> 'keep', '[]'::jsonb) @> to_jsonb(v_key) AND $owns(v_bag, v_key) THEN
                                v_o := v_o - v_key;
                            END IF;
                        END LOOP;
                    END LOOP;

                    -- The labels an open label field holds are recorded on the node, so the node knows which are
                    -- its own. A step that clears replaces the record, and drops the labels recorded that the
                    -- field no longer holds, but for those of the class; one that does not adds to the record.
                    v_add := COALESCE(v_op -> 'add', '[]'::jsonb);
                    v_drop := COALESCE(v_op -> 'drop', '[]'::jsonb);
                    v_owned := v_op -> 'owned';
                    IF v_owned IS NOT NULL THEN
                        v_recorded := v_o -> (v_owned ->> 'prop');
                        IF v_recorded IS NULL OR jsonb_typeof(v_recorded) <> 'array' THEN
                            v_recorded := '[]'::jsonb;
                        END IF;
                        IF COALESCE((v_owned ->> 'clear')::boolean, false) THEN
                            v_drop := v_drop || COALESCE((
                                SELECT jsonb_agg(l) FROM jsonb_array_elements_text(v_recorded) l
                                WHERE NOT v_add @> to_jsonb(l) AND NOT COALESCE(v_owned -> 'base', '[]'::jsonb) @> to_jsonb(l)), '[]'::jsonb);
                            IF jsonb_array_length(v_add) = 0 THEN
                                v_o := v_o - (v_owned ->> 'prop');
                            ELSE
                                v_o := v_o || jsonb_build_object(v_owned ->> 'prop', v_add);
                            END IF;
                        ELSIF jsonb_array_length(v_add) > 0 THEN
                            v_o := v_o || jsonb_build_object(v_owned ->> 'prop', COALESCE((
                                SELECT jsonb_agg(l ORDER BY l COLLATE "C") FROM jsonb_array_elements_text(v_recorded) l
                                WHERE NOT v_add @> to_jsonb(l)), '[]'::jsonb) || v_add);
                        END IF;
                    END IF;

                    -- The node's labels with those the step adds and without those it drops, in order; labels
                    -- that are as they were are not written again.
                    v_relabel := false;
                    IF jsonb_array_length(v_add) > 0 OR jsonb_array_length(v_drop) > 0 THEN
                        v_now := COALESCE((
                            SELECT jsonb_agg(l ORDER BY l COLLATE "C") FROM (
                                SELECT jsonb_array_elements_text(v_labels) AS l UNION SELECT jsonb_array_elements_text(v_add)
                            ) a WHERE NOT v_drop @> to_jsonb(l)), '[]'::jsonb);
                        v_relabel := NOT (v_now @> v_labels AND v_labels @> v_now);
                        IF v_relabel OR v_made THEN
                            v_labels := v_now;
                        END IF;
                    END IF;

                    IF v_made THEN
                        -- A node that is made is written once, whole, with a stamp of its own. A uniqueness
                        -- constraint may refuse it: once, it may be that another writer made the node at this
                        -- moment, and the node is looked for again. Found again or not, the constraint is on
                        -- something other than this node's key, and refuses this save.
                        v_stamp := v_fresh;
                        INSERT INTO $nodes (labels, props, stamp) VALUES (v_labels, v_o, v_stamp) ON CONFLICT DO NOTHING RETURNING nid INTO v_nid;
                        IF v_nid IS NOT NULL THEN
                            EXIT;
                        END IF;
                        v_tries := v_tries + 1;
                        IF v_tries > 1 THEN
                            INSERT INTO $nodes (labels, props, stamp) VALUES (v_labels, v_o, v_stamp);
                        END IF;
                    ELSE
                        v_nid := v_row.nid;
                        v_same := v_o = v_row.props;
                        -- A new first token only if the step changes the node, or the node has no stamp of two tokens yet.
                        v_two := ${twoTokens("v_stamp")};
                        IF v_two IS NULL OR NOT v_same OR v_relabel THEN
                            v_stamp := substr(v_fresh, 1, 16) || COALESCE(substr(v_two, 17), substr(v_fresh, 17));
                            IF v_relabel THEN
                                UPDATE $nodes SET labels = v_labels WHERE nid = v_nid;
                            END IF;
                            IF NOT v_same THEN
                                UPDATE $nodes SET props = v_o, stamp = v_stamp WHERE nid = v_nid;
                            ELSE
                                UPDATE $nodes SET stamp = v_stamp WHERE nid = v_nid;
                            END IF;
                        END IF;
                        EXIT;
                    END IF;
                END LOOP;

                v_nodes[(v_op ->> 'r')::int + 1] := v_nid;
                IF v_op ->> 'i' IS NOT NULL THEN
                    v_h_index := array_append(v_h_index, v_op ->> 'i');
                    -- Nothing for a node the step made, and a mark for one that was there without a stamp.
                    v_h_found := array_append(v_h_found, CASE WHEN v_made THEN NULL ELSE COALESCE(v_found, '$NEVER_STAMPED') END);
                    v_h_node := array_append(v_h_node, v_nid);
                END IF;
                IF COALESCE((v_op ->> 'root')::boolean, false) THEN
                    v_root := v_nid;
                    v_root_made := v_made;
                    v_was := v_found;
                    v_carried := v_op ->> 'carried';
                END IF;

            WHEN 'ref' THEN
                -- Finds a node that must be there, and locks it.
                v_shape := p_shapes -> ((v_op ->> 's')::int);
                v_row := $find(v_shape -> 'labels', v_shape ->> 'key', v_op ->> 'k');
                IF v_row.nid IS NULL THEN
                    RAISE EXCEPTION 'absent' USING ERRCODE = '$ABSENT';
                END IF;
                v_nodes[(v_op ->> 'r')::int + 1] := v_row.nid;

            WHEN 'link' THEN
                -- Joins two nodes. The relationship is as the step says only if every one of its type between them is.
                v_from := v_nodes[(v_op ->> 'from')::int + 1];
                v_to := v_nodes[(v_op ->> 'to')::int + 1];
                v_type := v_op ->> 'type';
                v_dir := v_op ->> 'dir';
                v_props := COALESCE(v_op -> 'props', '{}'::jsonb);
                v_clear := COALESCE(v_op -> 'clear', '[]'::jsonb);
                v_seen := 0;
                v_unchanged := 0;
                IF NOT COALESCE((v_op ->> 'always')::boolean, false) THEN
                    -- A node's relationship to itself is one relationship, whichever way it is read.
                    FOR v_e IN
                        SELECT eid, props FROM $edges
                        WHERE type = v_type AND (
                            (v_dir IN ('OUT', 'BOTH') AND src = v_from AND dst = v_to)
                            OR ((v_dir = 'IN' OR (v_dir = 'BOTH' AND v_from <> v_to)) AND src = v_to AND dst = v_from))
                        ORDER BY eid
                    LOOP
                        v_seen := v_seen + 1;
                        v_x := (v_e.props || v_props) - ARRAY(SELECT jsonb_array_elements_text(v_clear));
                        IF v_x = v_e.props THEN
                            v_unchanged := v_unchanged + 1;
                        ELSE
                            UPDATE $edges SET props = v_x WHERE eid = v_e.eid;
                        END IF;
                    END LOOP;
                END IF;
                IF v_seen = 0 THEN
                    -- An undirected field is satisfied by a relationship either way, and makes one from the first node.
                    INSERT INTO $edges (src, dst, type, props)
                    VALUES (CASE WHEN v_dir = 'IN' THEN v_to ELSE v_from END, CASE WHEN v_dir = 'IN' THEN v_from ELSE v_to END, v_type, v_props);
                END IF;
                IF v_seen = 0 OR v_unchanged < v_seen THEN
                    v_relinks := v_relinks || v_from || v_to;
                END IF;
                v_count := v_count + GREATEST(v_seen, 1);

            WHEN 'unlink' THEN
                -- Removes a field's relationships to the targets whose ids the step names, or to every target but those.
                v_from := v_nodes[(v_op ->> 'from')::int + 1];
                v_shape := p_shapes -> ((v_op ->> 's')::int);
                v_type := v_op ->> 'type';
                v_dir := v_op ->> 'dir';
                v_ids := COALESCE(v_op -> 'ids', '[]'::jsonb);
                v_held := COALESCE(v_op -> 'held', '[]'::jsonb);
                v_keep := COALESCE((v_op ->> 'keep')::boolean, false);
                v_delete := COALESCE((v_op ->> 'delete')::boolean, false);
                v_gone := '{}';
                v_targets := '{}';
                v_keys := '{}';
                -- A relationship from the node to itself is read as leaving it, and so once when both directions are read.
                FOR v_e IN
                    SELECT e.eid, t.nid, t.props ->> (v_shape ->> 'key') AS key
                    FROM $edges e JOIN $nodes t ON t.nid = e.dst
                    WHERE v_dir IN ('OUT', 'BOTH') AND e.src = v_from AND e.type = v_type AND t.labels @> (v_shape -> 'labels')
                    UNION ALL
                    SELECT e.eid, t.nid, t.props ->> (v_shape ->> 'key')
                    FROM $edges e JOIN $nodes t ON t.nid = e.src
                    WHERE v_dir IN ('IN', 'BOTH') AND e.dst = v_from AND e.type = v_type AND t.labels @> (v_shape -> 'labels')
                      AND (v_dir = 'IN' OR e.src <> v_from)
                LOOP
                    IF v_e.key IS NOT NULL AND (v_ids @> to_jsonb(v_e.key)) <> v_keep THEN
                        v_gone := array_append(v_gone, v_e.eid);
                        v_targets := array_append(v_targets, v_e.nid);
                        v_keys := array_append(v_keys, v_e.key);
                    END IF;
                END LOOP;
                IF cardinality(v_gone) > 0 THEN
                    FOR v_target IN SELECT DISTINCT t FROM unnest(v_targets) t ORDER BY t LOOP
                        -- A target gone since the relationships were read has nothing left to remove or count.
                        PERFORM 1 FROM $nodes WHERE nid = v_target FOR UPDATE;
                        IF NOT FOUND THEN
                            CONTINUE;
                        END IF;
                        -- What refers to a target is a relationship on the side this field reaches it from: counted before any is removed.
                        v_refs := 0;
                        IF v_delete THEN
                            IF v_dir IN ('OUT', 'BOTH') THEN
                                SELECT count(*) INTO v_refs FROM $edges WHERE dst = v_target;
                            END IF;
                            IF v_dir IN ('IN', 'BOTH') THEN
                                -- A relationship from the node to itself is counted once.
                                SELECT v_refs + count(*) INTO v_refs FROM $edges WHERE src = v_target AND (v_dir = 'IN' OR dst <> v_target);
                            END IF;
                        END IF;
                        SELECT count(*) INTO v_removed FROM unnest(v_targets) t WHERE t = v_target;
                        SELECT k INTO v_key FROM unnest(v_targets, v_keys) AS u (t, k) WHERE t = v_target LIMIT 1;
                        -- The root is never deleted, and nor is a node the object still holds in another field.
                        IF v_delete AND v_target <> v_from AND v_refs = v_removed AND NOT v_held @> to_jsonb(v_key) THEN
                            v_relinks := array_remove(v_relinks, v_target);
                            DELETE FROM $nodes WHERE nid = v_target;
                        ELSE
                            v_relinks := array_append(v_relinks, v_target);
                        END IF;
                    END LOOP;
                    DELETE FROM $edges WHERE eid = ANY (v_gone);
                    v_relinks := array_append(v_relinks, v_from);
                    v_count := v_count + cardinality(v_gone);
                END IF;

            WHEN 'cut' THEN
                -- Removes the relationships of a type from one node to another, or with no other node named to any in a direction.
                v_from := v_nodes[(v_op ->> 'from')::int + 1];
                v_to := CASE WHEN v_op ->> 'to' IS NOT NULL THEN v_nodes[(v_op ->> 'to')::int + 1] END;
                v_type := v_op ->> 'type';
                v_dir := v_op ->> 'dir';
                SELECT COALESCE(array_agg(c.eid), '{}'), COALESCE(array_agg(c.far), '{}') INTO v_gone, v_targets FROM (
                    SELECT eid, dst AS far FROM $edges
                    WHERE v_dir IN ('OUT', 'BOTH') AND src = v_from AND type = v_type AND (v_to IS NULL OR dst = v_to)
                    UNION ALL
                    SELECT eid, src FROM $edges
                    WHERE v_dir IN ('IN', 'BOTH') AND dst = v_from AND type = v_type AND (v_to IS NULL OR src = v_to) AND (v_dir = 'IN' OR src <> v_from)
                ) c;
                IF cardinality(v_gone) > 0 THEN
                    FOR v_target IN SELECT DISTINCT t FROM unnest(v_targets) t ORDER BY t LOOP
                        -- An end gone since the relationships were read took them with it, and has no token to change.
                        PERFORM 1 FROM $nodes WHERE nid = v_target FOR UPDATE;
                        IF FOUND THEN
                            v_relinks := array_append(v_relinks, v_target);
                        END IF;
                    END LOOP;
                    DELETE FROM $edges WHERE eid = ANY (v_gone);
                    v_relinks := array_append(v_relinks, v_from);
                    v_count := v_count + cardinality(v_gone);
                END IF;

            WHEN 'patch' THEN
                -- Writes properties to a node that is there, and makes none. One that is not there is left out of
                -- what the call hands back; one that does not meet the predicate is left as it is, and said to.
                v_shape := p_shapes -> ((v_op ->> 's')::int);
                v_fresh := v_op ->> 'fresh';
                v_row := $find(v_shape -> 'labels', v_shape ->> 'key', v_op ->> 'k');
                IF v_row.nid IS NOT NULL THEN
                    v_met := 1;
                    IF p_patched_if IS NOT NULL THEN
                        -- The node is locked: what the predicate reads of it is what the last writer committed.
                        EXECUTE p_patched_if INTO v_met USING v_row.nid;
                    END IF;
                    v_o := v_row.props;
                    IF v_met > 0 THEN
                        v_o := (v_o || COALESCE(v_op -> 'set', '{}'::jsonb)) - ARRAY(SELECT jsonb_array_elements_text(COALESCE(v_op -> 'unset', '[]'::jsonb)));
                    END IF;
                    v_same := v_o = v_row.props;
                    v_two := ${twoTokens("v_row.stamp")};
                    IF v_two IS NULL OR NOT v_same THEN
                        v_stamp := substr(v_fresh, 1, 16) || COALESCE(substr(v_two, 17), substr(v_fresh, 17));
                        IF NOT v_same THEN
                            UPDATE $nodes SET props = v_o, stamp = v_stamp WHERE nid = v_row.nid;
                        ELSE
                            UPDATE $nodes SET stamp = v_stamp WHERE nid = v_row.nid;
                        END IF;
                    END IF;
                    v_h_index := array_append(v_h_index, v_op ->> 'i');
                    v_h_found := array_append(v_h_found, CASE WHEN v_met = 0 THEN '${PatchOutcomes.UNMET}' WHEN NOT v_same THEN '${PatchOutcomes.CHANGED}' ELSE '${PatchOutcomes.UNCHANGED}' END);
                    v_h_node := array_append(v_h_node, v_row.nid);
                END IF;
            END CASE;
        END LOOP;

        -- Each node whose relationships changed gets a new second token, once, in the order of the nodes.
        -- A stamp that is not two tokens, which no save leaves, is replaced whole.
        FOR v_target IN SELECT DISTINCT t FROM unnest(v_relinks) t ORDER BY t LOOP
            UPDATE $nodes SET stamp = COALESCE(substr(${twoTokens("stamp")}, 1, 17), substr(p_mark, 1, 17)) || substr(p_mark, 18) WHERE nid = v_target;
        END LOOP;

        IF p_returns = 'STAMPS' THEN
            -- The root's stamp, keeping the object's own token wherever the node had another when the save
            -- began, then `index/found=stamp` for each node whose stamp is handed back.
            SELECT stamp INTO v_stamp FROM $nodes WHERE nid = v_root;
            IF v_carried IS NULL THEN
                -- An object that carried no stamp is handed the whole stamp only of a node its save made.
                IF NOT v_root_made THEN
                    v_stamp := substr(v_stamp, 1, 17);
                END IF;
            ELSIF v_was = v_carried THEN
                NULL; -- found exactly as loaded: the node's stamp is handed back whole
            ELSE
                v_stamp := CASE WHEN substr(v_was, 1, 16) = substr(v_carried, 1, 16) THEN substr(v_stamp, 1, 16) ELSE substr(v_carried, 1, 16) END || ':' ||
                    CASE WHEN NULLIF(substr(v_was, 18), '') = NULLIF(substr(v_carried, 18), '') OR (v_root_made AND NULLIF(substr(v_carried, 18), '') IS NULL)
                         THEN substr(v_stamp, 18) ELSE substr(v_carried, 18) END;
            END IF;
            v_result := v_stamp;
            FOR v_i IN 1 .. cardinality(v_h_node) LOOP
                SELECT stamp INTO v_stamp FROM $nodes WHERE nid = v_h_node[v_i];
                v_result := v_result || ',' || v_h_index[v_i] || '/' || COALESCE(v_h_found[v_i], '') || '=' || COALESCE(v_stamp, '');
            END LOOP;
        ELSIF p_returns = 'EACH_STAMP' THEN
            FOR v_i IN 1 .. cardinality(v_h_node) LOOP
                SELECT stamp INTO v_stamp FROM $nodes WHERE nid = v_h_node[v_i];
                v_each := array_append(v_each, v_h_index[v_i] || '/' || COALESCE(v_h_found[v_i], '') || '=' || COALESCE(v_stamp, ''));
            END LOOP;
        END IF;
    EXCEPTION
        WHEN SQLSTATE '$STALE' THEN v_stale := true; -- rolled back: nothing was written
        WHEN SQLSTATE '$ABSENT' THEN v_count := 0;   -- a node the plan refers to is not there: rolled back, nothing was written
    END;
    IF p_returns = 'STAMPS' THEN
        IF NOT v_stale AND v_result IS NOT NULL THEN
            RETURN NEXT v_result;
        END IF;
    ELSIF p_returns = 'EACH_STAMP' THEN
        IF NOT v_stale THEN
            RETURN QUERY SELECT unnest(v_each);
        END IF;
    ELSE
        RETURN NEXT v_count::text;
    END IF;
END
${'$'}fn${'$'} LANGUAGE plpgsql
""".trim()

    /** [stamp] where it is two tokens, as every save leaves one, and null where it is not. */
    private fun twoTokens(stamp: String): String = "CASE WHEN length($stamp) = 33 AND substr($stamp, 17, 1) = ':' THEN $stamp END"

    companion object {
        /** The state a stale root is raised as, and caught. */
        const val STALE = "P0D01"

        /** The state a node the plan needs and does not find is raised as, and caught. */
        const val ABSENT = "P0D02"

        private const val NEVER_STAMPED = Stamps.NEVER_STAMPED
    }
}
