-- Wrong rows: an OR of two correlated EXISTS subqueries gives only the rows the first one matches.
-- Oracle Database 23ai Free (gvenzl/oracle-free:23-faststart, 23.26), as an ordinary user in FREEPDB1.
-- Cause: subquery coalescing (COALESCE_SQ) merges the two subqueries wrongly where a multivalue index over the labels
-- answers their JSON_EXISTS. Without that index the rows are right. Each subquery alone is right; NO_COALESCE_SQ in each
-- subquery, or NO_QUERY_TRANSFORMATION, gives the right rows. NO_COALESCE_SQ at the statement level does not help.
-- The compiler writes NO_COALESCE_SQ in every subquery it uses as a predicate on Oracle (drivine-cypher-spec#252).

CREATE TABLE c_node (
    nid    NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    labels JSON NOT NULL,
    props  JSON NOT NULL
);
CREATE TABLE c_edge (
    eid  NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    src  NUMBER NOT NULL REFERENCES c_node (nid),
    dst  NUMBER NOT NULL REFERENCES c_node (nid),
    type VARCHAR2(100) NOT NULL
);
CREATE MULTIVALUE INDEX c_node_labels ON c_node n (n.labels.string());
INSERT INTO c_node (labels, props) VALUES (JSON('["Guide"]'), JSON('{"id": "g1"}'));
INSERT INTO c_node (labels, props) VALUES (JSON('["User", "Anonymous"]'), JSON('{"id": "u1"}'));
INSERT INTO c_node (labels, props) VALUES (JSON('["Guide"]'), JSON('{"id": "g2"}'));
INSERT INTO c_node (labels, props) VALUES (JSON('["User", "Registered"]'), JSON('{"id": "u2"}'));
INSERT INTO c_edge (src, dst, type) SELECT g.nid, u.nid, 'IS' FROM c_node g, c_node u
    WHERE JSON_VALUE(g.props, '$.id') = 'g1' AND JSON_VALUE(u.props, '$.id') = 'u1';
INSERT INTO c_edge (src, dst, type) SELECT g.nid, u.nid, 'IS' FROM c_node g, c_node u
    WHERE JSON_VALUE(g.props, '$.id') = 'g2' AND JSON_VALUE(u.props, '$.id') = 'u2';
COMMIT;

-- 1. Wrong: gives g1 alone (expected: g1, g2).
SELECT JSON_VALUE(g.props, '$.id') AS id FROM c_node g
WHERE JSON_EXISTS(g.labels, '$[*]?(@ == "Guide")')
  AND (EXISTS (SELECT 1 FROM c_edge e JOIN c_node u ON u.nid = e.dst WHERE e.src = g.nid AND e.type = 'IS' AND JSON_EXISTS(u.labels, '$[*]?(@ == "Anonymous")'))
    OR EXISTS (SELECT 1 FROM c_edge e JOIN c_node u ON u.nid = e.dst WHERE e.src = g.nid AND e.type = 'IS' AND JSON_EXISTS(u.labels, '$[*]?(@ == "Registered")')))
ORDER BY 1;

-- Right: NO_COALESCE_SQ in each subquery gives g1, g2.
SELECT JSON_VALUE(g.props, '$.id') AS id FROM c_node g
WHERE JSON_EXISTS(g.labels, '$[*]?(@ == "Guide")')
  AND (EXISTS (SELECT /*+ NO_COALESCE_SQ */ 1 FROM c_edge e JOIN c_node u ON u.nid = e.dst WHERE e.src = g.nid AND e.type = 'IS' AND JSON_EXISTS(u.labels, '$[*]?(@ == "Anonymous")'))
    OR EXISTS (SELECT /*+ NO_COALESCE_SQ */ 1 FROM c_edge e JOIN c_node u ON u.nid = e.dst WHERE e.src = g.nid AND e.type = 'IS' AND JSON_EXISTS(u.labels, '$[*]?(@ == "Registered")')))
ORDER BY 1;
