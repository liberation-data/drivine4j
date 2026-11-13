-- ORA-03113 (the server process ends) at execution: an aggregate's KEEP (DENSE_RANK FIRST ORDER BY …) ordered by a value
-- of the JSON type. Oracle Database 23ai Free (gvenzl/oracle-free:23-faststart, 23.26), as an ordinary user in FREEPDB1.
-- Found by drivine4j's layout-2 group queries: the least or greatest value's node was kept with KEEP, ordered by the
-- property's JSON as the Cypher compiler orders it. FIRST_VALUE(…) OVER (… ORDER BY the same) runs, and is what
-- drivine4j uses.

CREATE TABLE k_node (
    nid   NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    props JSON NOT NULL
);
INSERT INTO k_node (props) VALUES (JSON('{"page": "a", "stars": 2}'));
INSERT INTO k_node (props) VALUES (JSON('{"page": "a", "stars": 4}'));
INSERT INTO k_node (props) VALUES (JSON('{"page": "b"}'));
COMMIT;

-- 1. Ends the server process (client: ORA-03113).
SELECT JSON_VALUE(n.props, '$.page') AS k,
       MIN(n.nid) KEEP (DENSE_RANK FIRST ORDER BY JSON_QUERY(n.props, '$.stars' RETURNING JSON)) AS h
FROM k_node n
GROUP BY JSON_VALUE(n.props, '$.page');

-- Runs: the same order by a value that is no JSON, or as a window.
SELECT JSON_VALUE(n.props, '$.page') AS k,
       MIN(n.nid) KEEP (DENSE_RANK FIRST ORDER BY JSON_VALUE(n.props, '$.stars' RETURNING NUMBER)) AS h
FROM k_node n
GROUP BY JSON_VALUE(n.props, '$.page');
SELECT DISTINCT JSON_VALUE(n.props, '$.page') AS k,
       FIRST_VALUE(n.nid) OVER (PARTITION BY JSON_VALUE(n.props, '$.page') ORDER BY JSON_QUERY(n.props, '$.stars' RETURNING JSON)) AS h
FROM k_node n;
