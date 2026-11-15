-- ORA-07445 [kkocfbMarkBindFroCB()+56] [SIGSEGV] at hard parse (client: ORA-03113), and wrong rows with a literal.
-- Oracle Database 23ai Free (gvenzl/oracle-free:23-faststart, 23.26), as an ordinary user in FREEPDB1.
-- Cause: join elimination of a primary-key self-join through an inline view loses the filter on the eliminated side
-- when an index over that filter's expression exists (plain or unique index over JSON_VALUE(props, '$.id')).

CREATE TABLE t_node (
    nid    NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    labels JSON NOT NULL,
    props  JSON NOT NULL
);
CREATE INDEX t_ix ON t_node (JSON_VALUE(props, '$.id'));
INSERT INTO t_node (labels, props)
    SELECT JSON('["Doc"]'), JSON_OBJECT('id' VALUE CHR(96 + level) RETURNING JSON) FROM dual CONNECT BY level <= 3;
COMMIT;

-- 1. Bound value: the server process ends (ORA-03113 / ORA-07445 kkocfbMarkBindFroCB in the alert log).
SELECT c.nid FROM (SELECT a.nid FROM t_node a) m JOIN t_node c ON c.nid = m.nid
WHERE JSON_VALUE(c.props, '$.id') = :1;          -- bind 'a'

-- 2. Literal value: runs, but returns 1, 2, 3 (expected: 1). The filter is dropped.
SELECT c.nid FROM (SELECT a.nid FROM t_node a) m JOIN t_node c ON c.nid = m.nid
WHERE JSON_VALUE(c.props, '$.id') = 'a';

-- Answered right: no inline view; no index on the expression; only a unique index over a CASE around it;
-- the view row-limited (FETCH FIRST, not merged); or hints NO_ELIMINATE_JOIN(c) / NO_MERGE(m).
SELECT c.nid FROM t_node a JOIN t_node c ON c.nid = a.nid WHERE JSON_VALUE(c.props, '$.id') = :1;
SELECT /*+ NO_ELIMINATE_JOIN(c) */ c.nid FROM (SELECT a.nid FROM t_node a) m JOIN t_node c ON c.nid = m.nid
WHERE JSON_VALUE(c.props, '$.id') = :1;

-- As drivine4j met it (vector search candidates joined back to the node table, caller's filter outside):
-- SELECT JSON_OBJECT('value' VALUE n_.doc, 'score' VALUE n_.score RETURNING JSON) AS result
-- FROM (SELECT c.nid, c.labels, c.props, c.stamp, JSON_TRANSFORM(...) AS doc, m.score
--       FROM (SELECT c.nid, (2 - k.distance) / 2 AS score
--             FROM (SELECT s.nid, VECTOR_DISTANCE(s.embedding, TO_VECTOR(:1), COSINE) AS distance
--                   FROM <side> s ORDER BY distance FETCH APPROX FIRST :2 ROWS ONLY) k
--             JOIN <g>_node c ON c.nid = k.nid WHERE <label test>) m
--       JOIN <g>_node c ON c.nid = m.nid) n_
-- WHERE JSON_VALUE(n_.props, '$.id') = :3 ORDER BY n_.score DESC, n_.nid
