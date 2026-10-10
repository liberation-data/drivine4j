package org.drivine.spike

/**
 * The hand-written SQL of the Oracle write spike: a fixed schema, the SQL property graph over it,
 * and each save as one PL/SQL block.
 *
 * A block is one statement to the driver. Oracle rolls a block that fails back to where it began, so
 * a save is applied whole or not at all, with or without a transaction around it — what the Cypher
 * save statement gives on the graph engines.
 *
 * A block returns what the Cypher statement returns: the root's stamp, then `index/found=stamp` for
 * each related node, joined by commas; null when a checked root is not as the object's stamp says.
 */
internal object OracleSpikeSql {

    val SCHEMA = listOf(
        "CREATE TABLE claim (id VARCHAR2(64) PRIMARY KEY, text VARCHAR2(400), stamp VARCHAR2(33) NOT NULL)",
        """
        CREATE TABLE human (
            id VARCHAR2(64) PRIMARY KEY, name VARCHAR2(100), stamp VARCHAR2(33) NOT NULL,
            CONSTRAINT human_name_ck CHECK (name IS NULL OR name NOT LIKE 'FAIL%')
        )
        """,
        """
        CREATE TABLE mentions (
            claim_id VARCHAR2(64) NOT NULL REFERENCES claim (id),
            human_id VARCHAR2(64) NOT NULL REFERENCES human (id),
            PRIMARY KEY (claim_id, human_id)
        )
        """,
        // A named entity: labels and properties are data, so they are JSON columns.
        """
        CREATE TABLE entity (
            id VARCHAR2(64) PRIMARY KEY, name VARCHAR2(400),
            labels JSON NOT NULL, props JSON NOT NULL, stamp VARCHAR2(33) NOT NULL
        )
        """,
        """
        CREATE PROPERTY GRAPH spike
            VERTEX TABLES (
                claim KEY (id) LABEL Claim PROPERTIES (id, text, stamp),
                human KEY (id) LABEL Human PROPERTIES (id, name, stamp),
                entity KEY (id) LABEL Entity PROPERTIES (id, name, labels, props, stamp)
            )
            EDGE TABLES (
                mentions KEY (claim_id, human_id)
                    SOURCE KEY (claim_id) REFERENCES claim (id)
                    DESTINATION KEY (human_id) REFERENCES human (id)
                    LABEL MENTIONS NO PROPERTIES
            )
        """,
    )

    val CLEAN = listOf("DELETE FROM mentions", "DELETE FROM claim", "DELETE FROM human", "DELETE FROM entity")

    /**
     * Saves a claim and the people it mentions.
     *
     * In: id, text, expected stamp (null for an unchecked save), new stamp, people as a JSON array of
     * `{id, name, fresh}`, and 1 when the list is the whole list. Out: the stamps.
     *
     * Each node is locked before it is read (`FOR UPDATE`), so what the block compares is what the
     * last writer committed. People are locked in id order, so two saves that share people cannot
     * each wait on the other.
     */
    val SAVE_VIEW = """
        DECLARE
            TYPE t_ids IS TABLE OF VARCHAR2(64);
            v_id       VARCHAR2(64)    := ?;
            v_text     VARCHAR2(400)   := ?;
            v_expected VARCHAR2(33)    := ?;
            v_fresh    VARCHAR2(33)    := ?;
            v_people   VARCHAR2(32767) := ?;
            v_replace  NUMBER          := ?;
            v_found    VARCHAR2(33);
            v_was      VARCHAR2(400);
            v_node     VARCHAR2(16);
            v_links    VARCHAR2(16);
            v_h_found  VARCHAR2(33);
            v_h_was    VARCHAR2(100);
            v_h_node   VARCHAR2(16);
            v_h_links  VARCHAR2(16);
            v_h_stamp  VARCHAR2(33);
            v_dropped  t_ids;
            v_linked   BOOLEAN := FALSE;
            v_out      VARCHAR2(32767);
            v_result   VARCHAR2(32767);
            e_stale    EXCEPTION;
        BEGIN
        BEGIN
            -- The root: locked if it is there, made if it is not.
            LOOP
                BEGIN
                    SELECT stamp, text INTO v_found, v_was FROM claim WHERE id = v_id FOR UPDATE;
                    EXIT;
                EXCEPTION WHEN NO_DATA_FOUND THEN
                    IF v_expected IS NOT NULL THEN RAISE e_stale; END IF;
                    BEGIN
                        INSERT INTO claim (id, text, stamp) VALUES (v_id, v_text, v_fresh);
                        v_found := NULL;
                        v_was := v_text;
                        EXIT;
                    EXCEPTION WHEN DUP_VAL_ON_INDEX THEN NULL; -- another writer made it: lock theirs
                    END;
                END;
            END LOOP;
            IF v_expected IS NOT NULL AND SUBSTR(v_expected, 1, 16) <> SUBSTR(v_found, 1, 16) THEN
                RAISE e_stale;
            END IF;
            v_node := SUBSTR(NVL(v_found, v_fresh), 1, 16);
            v_links := SUBSTR(NVL(v_found, v_fresh), 18);
            IF NVL(v_was, CHR(0)) <> NVL(v_text, CHR(0)) THEN
                v_node := SUBSTR(v_fresh, 1, 16);
            END IF;

            -- The relationships the list no longer holds. Each marks the node at its other end.
            IF v_replace = 1 THEN
                DELETE FROM mentions
                WHERE claim_id = v_id
                  AND human_id NOT IN (SELECT id FROM JSON_TABLE(v_people, '$[*]' COLUMNS (id VARCHAR2(64) PATH '$.id')))
                RETURNING human_id BULK COLLECT INTO v_dropped;
                IF v_dropped.COUNT > 0 THEN
                    v_linked := TRUE;
                    FORALL i IN 1 .. v_dropped.COUNT
                        UPDATE human SET stamp = SUBSTR(stamp, 1, 17) || SUBSTR(v_fresh, 18) WHERE id = v_dropped(i);
                END IF;
            END IF;

            -- Each related node, and the relationship to it.
            FOR p IN (
                SELECT idx, id, name, fresh
                FROM JSON_TABLE(v_people, '$[*]' COLUMNS (
                    idx FOR ORDINALITY, id VARCHAR2(64) PATH '$.id',
                    name VARCHAR2(100) PATH '$.name', fresh VARCHAR2(33) PATH '$.fresh'))
                ORDER BY id
            ) LOOP
                LOOP
                    BEGIN
                        SELECT stamp, name INTO v_h_found, v_h_was FROM human WHERE id = p.id FOR UPDATE;
                        EXIT;
                    EXCEPTION WHEN NO_DATA_FOUND THEN
                        BEGIN
                            INSERT INTO human (id, name, stamp) VALUES (p.id, p.name, p.fresh);
                            v_h_found := NULL;
                            v_h_was := p.name;
                            EXIT;
                        EXCEPTION WHEN DUP_VAL_ON_INDEX THEN NULL;
                        END;
                    END;
                END LOOP;
                v_h_node := SUBSTR(NVL(v_h_found, p.fresh), 1, 16);
                v_h_links := SUBSTR(NVL(v_h_found, p.fresh), 18);
                IF NVL(v_h_was, CHR(0)) <> NVL(p.name, CHR(0)) THEN
                    v_h_node := SUBSTR(p.fresh, 1, 16);
                END IF;
                BEGIN
                    INSERT INTO mentions (claim_id, human_id) VALUES (v_id, p.id);
                    v_linked := TRUE;
                    v_h_links := SUBSTR(p.fresh, 18);
                EXCEPTION WHEN DUP_VAL_ON_INDEX THEN NULL; -- there already
                END;
                v_h_stamp := v_h_node || ':' || v_h_links;
                IF v_h_found IS NULL OR v_h_stamp <> v_h_found THEN
                    UPDATE human SET name = p.name, stamp = v_h_stamp WHERE id = p.id;
                END IF;
                v_out := v_out || ',' || (p.idx - 1) || '/' || v_h_found || '=' || v_h_stamp;
            END LOOP;

            IF v_linked THEN
                v_links := SUBSTR(v_fresh, 18);
            END IF;
            UPDATE claim SET text = v_text, stamp = v_node || ':' || v_links WHERE id = v_id;
            v_result := v_node || ':' || v_links || v_out;
        EXCEPTION WHEN e_stale THEN
            v_result := NULL; -- raised before anything was written
        END;
            ? := v_result;
        END;
    """.trimIndent()

    /**
     * Saves a named entity: labels are added and none removed, properties are merged into those there
     * (a null clears one). In: id, name, labels as a JSON array, properties as a JSON object, new
     * stamp. Out: the stamp.
     */
    val SAVE_ENTITY = """
        DECLARE
            v_id         VARCHAR2(64)   := ?;
            v_name       VARCHAR2(400)  := ?;
            v_labels     VARCHAR2(4000) := ?;
            v_props      VARCHAR2(4000) := ?;
            v_fresh      VARCHAR2(33)   := ?;
            v_found      VARCHAR2(33);
            v_was_name   VARCHAR2(400);
            v_was_labels VARCHAR2(4000);
            v_was_props  VARCHAR2(4000);
            v_new_labels VARCHAR2(4000);
            v_new_props  VARCHAR2(4000);
            v_same       NUMBER;
            v_stamp      VARCHAR2(33);
        BEGIN
            LOOP
                BEGIN
                    SELECT stamp, name, JSON_SERIALIZE(labels), JSON_SERIALIZE(props)
                    INTO v_found, v_was_name, v_was_labels, v_was_props
                    FROM entity WHERE id = v_id FOR UPDATE;
                    EXIT;
                EXCEPTION WHEN NO_DATA_FOUND THEN
                    BEGIN
                        INSERT INTO entity (id, name, labels, props, stamp) VALUES (v_id, v_name, '[]', '{}', v_fresh);
                    EXCEPTION WHEN DUP_VAL_ON_INDEX THEN NULL;
                    END;
                END;
            END LOOP;

            SELECT JSON_ARRAYAGG(l ORDER BY l) INTO v_new_labels FROM (
                SELECT l FROM JSON_TABLE(v_was_labels, '$[*]' COLUMNS (l VARCHAR2(200) PATH '$'))
                UNION
                SELECT l FROM JSON_TABLE(v_labels, '$[*]' COLUMNS (l VARCHAR2(200) PATH '$'))
            );
            SELECT JSON_MERGEPATCH(v_was_props, v_props) INTO v_new_props FROM dual;
            SELECT CASE WHEN JSON_EQUAL(v_was_labels, v_new_labels) AND JSON_EQUAL(v_was_props, v_new_props) THEN 1 ELSE 0 END
            INTO v_same FROM dual;

            v_stamp := v_found;
            IF v_same = 0 OR NVL(v_was_name, CHR(0)) <> NVL(v_name, CHR(0)) THEN
                v_stamp := SUBSTR(v_fresh, 1, 16) || SUBSTR(v_found, 17);
                UPDATE entity SET name = v_name, labels = v_new_labels, props = v_new_props, stamp = v_stamp WHERE id = v_id;
            END IF;
            ? := v_stamp;
        END;
    """.trimIndent()

    /** A claim and the people it mentions as one JSON document, read through the property graph. */
    val LOAD_VIEW = """
        SELECT JSON_OBJECT(
            'id' VALUE root.id, 'text' VALUE root.text, 'stamp' VALUE root.stamp,
            'people' VALUE (
                SELECT JSON_ARRAYAGG(JSON_OBJECT('id' VALUE g.hid, 'name' VALUE g.hname, 'stamp' VALUE g.hstamp) ORDER BY g.hid)
                FROM GRAPH_TABLE (spike
                    MATCH (c IS Claim) -[IS MENTIONS]-> (h IS Human)
                    COLUMNS (c.id AS cid, h.id AS hid, h.name AS hname, h.stamp AS hstamp)) g
                WHERE g.cid = root.id
            ) FORMAT JSON
        )
        FROM GRAPH_TABLE (spike MATCH (c IS Claim) WHERE c.id = ? COLUMNS (c.id AS id, c.text AS text, c.stamp AS stamp)) root
    """.trimIndent()

    /** The ids of the entities that carry a label and whose `born` is before a year, through the graph. */
    val FIND_ENTITIES = """
        SELECT id FROM GRAPH_TABLE (spike
            MATCH (e IS Entity)
            WHERE JSON_EXISTS(e.labels, '$[*]?(@ == ${'$'}l)' PASSING ? AS "l")
              AND JSON_VALUE(e.props, '$.born' RETURNING NUMBER) < ?
            COLUMNS (e.id AS id))
        ORDER BY id
    """.trimIndent()

    val LOAD_ENTITY = """
        SELECT JSON_OBJECT('name' VALUE name, 'labels' VALUE labels, 'props' VALUE props, 'stamp' VALUE stamp)
        FROM GRAPH_TABLE (spike MATCH (e IS Entity) WHERE e.id = ?
            COLUMNS (e.name AS name, e.labels AS labels, e.props AS props, e.stamp AS stamp))
    """.trimIndent()
}
